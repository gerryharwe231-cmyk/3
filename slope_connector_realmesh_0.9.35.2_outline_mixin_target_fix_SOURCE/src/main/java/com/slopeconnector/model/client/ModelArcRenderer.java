package com.slopeconnector.model.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcComponentFinder;
import com.slopeconnector.model.ArcCrossSectionMapping;
import com.slopeconnector.model.ArcPrismTags;
import com.slopeconnector.model.ArcStationFrames;
import com.slopeconnector.model.ModelBlockEntity;
import com.slopeconnector.model.ModelStateResolver;
import com.slopeconnector.surface.ConnectionStateHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 0.9.24.3 model deformation pipeline.
 *
 * The captured BakedModel is treated as one real 1x1x1 source module.  It is clipped to that
 * source cell, sliced finely along its longitudinal axis, then each new vertex is transported over
 * the already-generated Model-Block arc.  Source sprite UVs are never regenerated.
 *
 * Important invariants:
 *  - source geometry outside 0..1 is clipped before repetition, so connected-model arms cannot
 *    overlap several neighboring modules;
 *  - the whole arc uses one continuously transported cross-section frame;
 *  - source/target handedness is audited per triangle, preventing mirrored/cull-disappearing faces;
 *  - every small triangle is owned by the ArcRibbon block nearest its actual arc-length position,
 *    rather than by the coarse module midpoint.
 */
public final class ModelArcRenderer {
    private static final int MAX_MODULES = 4096;
    private static final double EPS = 1.0E-8;
    private static final double OWNER_RENDER_RADIUS = 48.0;
    private static final double OWNER_RENDER_RADIUS_SQ = OWNER_RENDER_RADIUS * OWNER_RENDER_RADIUS;
    private static final Map<ArcRibbonBlockEntity, MeshHandle> CACHE = new WeakHashMap<>();

    private ModelArcRenderer() {}

    public static boolean renderReplacement(ArcRibbonBlockEntity entity, float tickDelta,
                                            MatrixStack matrices, VertexConsumerProvider consumers,
                                            int fallbackLight, int overlay) {
        if (entity.getWorld() == null) return false;
        MeshHandle handle;
        try {
            handle = handle(entity);
        } catch (RuntimeException error) {
            return false;
        }
        if (handle == null) return false;
        boolean consolidated = handle.renderLeader != null;
        if (consolidated && !handle.renderLeader.equals(entity.getPos())) {
            return handle.memberRevisions.containsKey(entity.getPos());
        }

        BlockState state = handle.state;
        RenderLayer layer = RenderLayers.getBlockLayer(state);
        VertexConsumer consumer = consumers.getBuffer(layer);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos origin = entity.getPos();
        Map<BlockPos, int[]> lightByOwner = new HashMap<>();

        Vec3d viewer = MinecraftClient.getInstance().player == null
                ? Vec3d.ofCenter(entity.getPos()) : MinecraftClient.getInstance().player.getPos();
        Iterable<Map.Entry<BlockPos,List<Triangle>>> renderEntries = consolidated
                ? nearbyEntries(handle.byOwner, handle.ownersByChunk, viewer)
                : java.util.List.of(Map.entry(entity.getPos(),
                    handle.byOwner.getOrDefault(entity.getPos(), List.of())));
        boolean emittedAny = false;
        for (Map.Entry<BlockPos,List<Triangle>> ownerEntry : renderEntries) {
            if (ownerEntry.getValue().isEmpty()) continue;
            if (Vec3d.ofCenter(ownerEntry.getKey()).squaredDistanceTo(viewer) > OWNER_RENDER_RADIUS_SQ) continue;
            int[] directionalLights = lightByOwner.computeIfAbsent(ownerEntry.getKey(),
                    ignored -> new int[]{-1,-1,-1,-1,-1,-1});
            for (Triangle triangle : ownerEntry.getValue()) {
                emittedAny = true;
                int color = 0xFFFFFF;
                if (triangle.tintIndex >= 0) {
                    int sampled = MinecraftClient.getInstance().getBlockColors().getColor(
                            state, entity.getWorld(), BlockPos.ofFloored(triangle.worldCenter), triangle.tintIndex);
                    if (sampled != -1) color = sampled & 0xFFFFFF;
                }
                Direction lightDirection = dominantDirection(triangle.normal);
                int lightIndex = lightDirection.ordinal();
                int packedLight = directionalLights[lightIndex];
                if (packedLight < 0) {
                    packedLight = ModelRenderLighting.sample(entity.getWorld(), state,
                            Vec3d.ofCenter(ownerEntry.getKey()), directionVector(lightDirection), fallbackLight);
                    directionalLights[lightIndex] = packedLight;
                }
                int red = (color >> 16) & 255;
                int green = (color >> 8) & 255;
                int blue = color & 255;
                emit(consumer, position, normalMatrix, triangle.a, origin,
                        triangle.normal, packedLight, overlay, red, green, blue);
                emit(consumer, position, normalMatrix, triangle.b, origin,
                        triangle.normal, packedLight, overlay, red, green, blue);
                emit(consumer, position, normalMatrix, triangle.c, origin,
                        triangle.normal, packedLight, overlay, red, green, blue);
                emit(consumer, position, normalMatrix, triangle.c, origin,
                        triangle.normal, packedLight, overlay, red, green, blue);
            }
        }
        return emittedAny || handle.memberRevisions.containsKey(entity.getPos());
    }

    private static Iterable<Map.Entry<BlockPos,List<Triangle>>> nearbyEntries(
            Map<BlockPos,List<Triangle>> byOwner, Map<Long,List<BlockPos>> ownersByChunk, Vec3d viewer) {
        int centerX = ((int)Math.floor(viewer.x)) >> 4;
        int centerZ = ((int)Math.floor(viewer.z)) >> 4;
        int radius = (int)Math.ceil(OWNER_RENDER_RADIUS / 16.0) + 1;
        List<Map.Entry<BlockPos,List<Triangle>>> result = new ArrayList<>();
        for (int dx=-radius; dx<=radius; dx++) for (int dz=-radius; dz<=radius; dz++) {
            List<BlockPos> owners = ownersByChunk.get(chunkKey(centerX+dx, centerZ+dz));
            if (owners == null) continue;
            for (BlockPos owner : owners) {
                List<Triangle> triangles = byOwner.get(owner);
                if (triangles != null && !triangles.isEmpty()) result.add(Map.entry(owner, triangles));
            }
        }
        return result;
    }

    private static Map<Long,List<BlockPos>> chunkIndex(Map<BlockPos,? extends List<?>> byOwner) {
        Map<Long,List<BlockPos>> index = new HashMap<>();
        for (BlockPos pos : byOwner.keySet()) {
            index.computeIfAbsent(chunkKey(pos.getX()>>4, pos.getZ()>>4), ignored -> new ArrayList<>()).add(pos);
        }
        Map<Long,List<BlockPos>> frozen = new HashMap<>();
        index.forEach((key,value) -> frozen.put(key,List.copyOf(value)));
        return Map.copyOf(frozen);
    }

    private static long chunkKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    private static MeshHandle handle(ArcRibbonBlockEntity entity) {
        MeshHandle cached = CACHE.get(entity);
        if (cached != null && cached.matches(entity)) return cached;
        ArcComponentFinder.Component component = ArcComponentFinder.build(entity);
        if (component == null) return null;
        MeshHandle built = compile(component);
        for (ArcRibbonBlockEntity member : component.members()) CACHE.put(member, built);
        return built;
    }

    private static MeshHandle compile(ArcComponentFinder.Component component) {
        BlockState state = component.leader().getSourceState();
        BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
        List<BakedQuad> quads = collectQuads(model, state);
        if (quads.isEmpty()) return MeshHandle.empty(state);

        Direction.Axis sourceAxis = ModelStateResolver.longitudinalAxis(state);
        boolean sourceReverse = ModelStateResolver.reverseLongitudinal(state);
        List<PreparedQuad> preparedQuads = prepareQuads(quads, sourceAxis, sourceReverse);
        if (preparedQuads.isEmpty()) return MeshHandle.empty(state);
        CurveSampler curve = new CurveSampler(component, sourceAxis, sourceReverse);
        double total = curve.totalLength();
        if (total < EPS) return MeshHandle.empty(state);

        int moduleCount = Math.max(1, Math.min(MAX_MODULES, (int) Math.round(total)));
        double moduleLength = total / moduleCount;
        Frame referenceFrame = curve.sample(total * 0.5);
        int lateralTiles = tileCount(referenceFrame.lateralSpan);
        int verticalTiles = tileCount(referenceFrame.verticalSpan);
        boolean cullInternalTileFaces = ModelStateResolver.canCullInternalTileFaces(state);
        // Fence/Pane/Wall/iron-bar style modules still need their partial lateral/vertical boundary
        // faces, but the faces at q=0/q=1 are *longitudinal caps*.  Keeping those caps on every
        // repeated module produces the user's periodic paper-thin plates between otherwise-correct
        // railing bars.  Cull only longitudinal internal caps for connected profiles.
        boolean cullLongitudinalModuleCaps = cullInternalTileFaces
                || ConnectionStateHelper.isSupported(state);
        Map<BlockPos, List<Triangle>> byOwner = new HashMap<>();

        for (int module = 0; module < moduleCount; module++) {
            double moduleStart = module * moduleLength;
            double sourceSlice = sourceSliceForModule(state, curve, moduleStart, moduleLength,
                    lateralTiles * verticalTiles);
            for (int lateralTile = 0; lateralTile < lateralTiles; lateralTile++) {
                for (int verticalTile = 0; verticalTile < verticalTiles; verticalTile++) {
                    for (PreparedQuad prepared : preparedQuads) {
                        List<SourceVertex> polygon = prepared.polygon();
                        if (cullLongitudinalModuleCaps) {
                            if (prepared.qStart() && (module > 0 || component.startModelBlock() != null)) continue;
                            if (prepared.qEnd() && (module + 1 < moduleCount || component.endModelBlock() != null)) continue;
                        }
                        if (cullInternalTileFaces) {
                            if (prepared.lateralMin() && lateralTile > 0) continue;
                            if (prepared.lateralMax() && lateralTile + 1 < lateralTiles) continue;
                            if (prepared.verticalMin() && verticalTile > 0) continue;
                            if (prepared.verticalMax() && verticalTile + 1 < verticalTiles) continue;
                        }
                        subdivideAndWarp(polygon, prepared.quad(), moduleStart, moduleLength,
                                sourceAxis, curve, byOwner,
                                lateralTile, lateralTiles, verticalTile, verticalTiles, sourceSlice);
                    }
                }
            }
        }

        Map<BlockPos, Integer> revisions = new HashMap<>();
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (ArcRibbonBlockEntity member : component.members()) {
            revisions.put(member.getPos(), member.getRenderRevision());
            states.put(member.getPos(), member.getSourceState());
        }
        BlockPos renderLeader = null;
        for (ArcRibbonBlockEntity member : component.members()) {
            if (ArcPrismTags.isRenderLeader(member)) { renderLeader = member.getPos(); break; }
        }
        Map<BlockPos, List<Triangle>> frozen = new HashMap<>();
        byOwner.forEach((pos, list) -> frozen.put(pos, List.copyOf(list)));
        Map<BlockPos,List<Triangle>> frozenOwners = Map.copyOf(frozen);
        return new MeshHandle(state, Map.copyOf(revisions), Map.copyOf(states),
                frozenOwners, chunkIndex(frozenOwners), renderLeader);
    }

    private static List<BakedQuad> collectQuads(BakedModel model, BlockState state) {
        List<BakedQuad> result = new ArrayList<>();
        long seed = state.getRenderingSeed(BlockPos.ORIGIN);
        for (Direction face : Direction.values()) {
            result.addAll(model.getQuads(state, face, Random.create(seed + face.ordinal())));
        }
        result.addAll(model.getQuads(state, null, Random.create(seed + 91L)));
        return result;
    }

    private static List<PreparedQuad> prepareQuads(List<BakedQuad> quads,
                                                    Direction.Axis sourceAxis, boolean reverse) {
        List<PreparedQuad> result = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            List<SourceVertex> original = decode(quad, sourceAxis, reverse);
            if (original.size() < 3) continue;
            boolean qStart = isBoundaryFace(original, Axis.Q, 0.0);
            boolean qEnd = isBoundaryFace(original, Axis.Q, 1.0);
            boolean lateralMin = isBoundaryFace(original, Axis.LATERAL, -0.5);
            boolean lateralMax = isBoundaryFace(original, Axis.LATERAL, 0.5);
            boolean verticalMin = isBoundaryFace(original, Axis.VERTICAL, -0.5);
            boolean verticalMax = isBoundaryFace(original, Axis.VERTICAL, 0.5);
            List<SourceVertex> polygon = clip(original, Axis.Q, 0.0, true);
            polygon = clip(polygon, Axis.Q, 1.0, false);
            polygon = clip(polygon, Axis.LATERAL, -0.5, true);
            polygon = clip(polygon, Axis.LATERAL, 0.5, false);
            polygon = clip(polygon, Axis.VERTICAL, -0.5, true);
            polygon = clip(polygon, Axis.VERTICAL, 0.5, false);
            if (polygon.size() >= 3) {
                result.add(new PreparedQuad(quad, List.copyOf(polygon), qStart, qEnd,
                        lateralMin, lateralMax, verticalMin, verticalMax));
            }
        }
        return List.copyOf(result);
    }

    private static List<SourceVertex> decode(BakedQuad quad, Direction.Axis sourceAxis, boolean reverse) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        if (stride < 6) return List.of();
        List<SourceVertex> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float x = Float.intBitsToFloat(data[base]);
            float y = Float.intBitsToFloat(data[base + 1]);
            float z = Float.intBitsToFloat(data[base + 2]);
            float u = Float.intBitsToFloat(data[base + 4]);
            float v = Float.intBitsToFloat(data[base + 5]);
            double qRaw = sourceAxis == Direction.Axis.Z ? z : x;
            double q = reverse ? 1.0 - qRaw : qRaw;
            double lateral = sourceAxis == Direction.Axis.Z ? x - 0.5 : z - 0.5;
            double vertical = y - 0.5;
            out.add(new SourceVertex(q, lateral, vertical, u, v));
        }
        return out;
    }

    private static void subdivideAndWarp(List<SourceVertex> source, BakedQuad quad,
                                         double moduleStart, double moduleLength,
                                         Direction.Axis sourceAxis, CurveSampler curve,
                                         Map<BlockPos, List<Triangle>> byOwner,
                                         int lateralTile, int lateralTiles,
                                         int verticalTile, int verticalTiles,
                                         double sourceSlice) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (SourceVertex vertex : source) {
            min = Math.min(min, vertex.q);
            max = Math.max(max, vertex.q);
        }
        if (max - min < EPS) {
            emitPolygon(source, quad, moduleStart, moduleLength, sourceAxis, curve, byOwner, lateralTile, lateralTiles, verticalTile, verticalTiles);
            return;
        }
        int first = (int) Math.floor(min / sourceSlice);
        int last = (int) Math.ceil(max / sourceSlice) - 1;
        for (int cell = first; cell <= last; cell++) {
            double low = cell * sourceSlice;
            double high = (cell + 1) * sourceSlice;
            List<SourceVertex> clipped = clip(source, Axis.Q, low, true);
            clipped = clip(clipped, Axis.Q, high, false);
            if (clipped.size() >= 3) {
                emitPolygon(clipped, quad, moduleStart, moduleLength,
                        sourceAxis, curve, byOwner,
                        lateralTile, lateralTiles, verticalTile, verticalTiles);
            }
        }
    }

    private static double sourceSlice(BlockState state) {
        return ModelStateResolver.isStairOrSlab(state) || ConnectionStateHelper.isSupported(state)
                ? 1.0 / 8.0 : 1.0 / 4.0;
    }

    private static double sourceSliceForModule(BlockState state, CurveSampler curve,
                                                double moduleStart, double moduleLength,
                                                int crossSectionTiles) {
        // A straight one-block module needs no longitudinal slicing at all: the original BakedQuad
        // is already exact. Curved modules retain the finer stair/railing detail. This cuts triangle
        // counts drastically on straight leads without changing the visible curve section.
        double a = Math.max(0.0, moduleStart + Math.min(moduleLength * 0.02, 1.0E-3));
        double b = Math.min(curve.totalLength(), moduleStart + moduleLength - Math.min(moduleLength * 0.02, 1.0E-3));
        if (b > a + EPS) {
            double dot = curve.sample(a).tangent.dotProduct(curve.sample(b).tangent);
            if (dot > 0.99995) return 1.0;
        }
        double base = sourceSlice(state);
        // Cross-section repetition already multiplies triangle count by width*thickness.  Four
        // slices per curved one-block module remain visually smooth while preventing a 4x/8x wide
        // railing from exploding into thousands of tiny triangles per metre.
        if (crossSectionTiles >= 4) base = Math.max(base, 0.25);
        if (crossSectionTiles >= 9) base = Math.max(base, 0.50);
        if (crossSectionTiles >= 16) base = 1.0;
        return base;
    }

    private enum Axis { Q, LATERAL, VERTICAL }

    private static int tileCount(double span) {
        if (!Double.isFinite(span) || span < 1.0) return 1;
        return Math.max(1, Math.min(32, (int)Math.round(span)));
    }

    private static double coordinate(SourceVertex vertex, Axis axis) {
        return switch (axis) {
            case Q -> vertex.q;
            case LATERAL -> vertex.lateral;
            case VERTICAL -> vertex.vertical;
        };
    }

    private static boolean isBoundaryFace(List<SourceVertex> polygon, Axis axis, double boundary) {
        if (polygon.isEmpty()) return false;
        for (SourceVertex vertex : polygon) {
            if (Math.abs(coordinate(vertex, axis) - boundary) > 1.0E-5) return false;
        }
        return true;
    }

    private static List<SourceVertex> clip(List<SourceVertex> input, Axis axis, double boundary,
                                           boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<SourceVertex> out = new ArrayList<>();
        SourceVertex previous = input.get(input.size() - 1);
        double previousCoordinate = coordinate(previous, axis);
        boolean previousInside = keepGreater ? previousCoordinate >= boundary - EPS
                : previousCoordinate <= boundary + EPS;
        for (SourceVertex current : input) {
            double currentCoordinate = coordinate(current, axis);
            boolean inside = keepGreater ? currentCoordinate >= boundary - EPS
                    : currentCoordinate <= boundary + EPS;
            if (inside != previousInside) {
                double denominator = currentCoordinate - previousCoordinate;
                double t = Math.abs(denominator) < 1.0E-12
                        ? 0.0 : (boundary - previousCoordinate) / denominator;
                out.add(previous.lerp(current, t));
            }
            if (inside) out.add(current);
            previous = current;
            previousCoordinate = currentCoordinate;
            previousInside = inside;
        }
        return out;
    }

    private static void emitPolygon(List<SourceVertex> polygon, BakedQuad quad,
                                    double moduleStart, double moduleLength,
                                    Direction.Axis sourceAxis, CurveSampler curve,
                                    Map<BlockPos, List<Triangle>> byOwner,
                                    int lateralTile, int lateralTiles,
                                    int verticalTile, int verticalTiles) {
        WorldVertex first = warp(polygon.get(0), moduleStart, moduleLength, curve,
                lateralTile, lateralTiles, verticalTile, verticalTiles);
        for (int index = 1; index + 1 < polygon.size(); index++) {
            WorldVertex second = warp(polygon.get(index), moduleStart, moduleLength, curve, lateralTile, lateralTiles, verticalTile, verticalTiles);
            WorldVertex third = warp(polygon.get(index + 1), moduleStart, moduleLength, curve, lateralTile, lateralTiles, verticalTile, verticalTiles);

            double sAverage = (first.s + second.s + third.s) / 3.0;
            ArcRibbonBlockEntity owner = curve.ownerAt(sAverage);
            if (owner == null) continue;

            // The target cross-section may be mirrored relative to the source model coordinate
            // basis.  Reverse the triangle in that one case, preserving front/back winding without
            // changing which physical side is the requested inner arc.
            if (curve.reversesWinding(sourceAxis, sAverage)) {
                WorldVertex swap = second;
                second = third;
                third = swap;
            }

            Vector3f normal = normal(first, second, third);
            if (normal == null) continue;
            Vec3d center = first.world.add(second.world).add(third.world).multiply(1.0 / 3.0);
            byOwner.computeIfAbsent(owner.getPos(), key -> new ArrayList<>())
                    .add(new Triangle(first, second, third, normal,
                            quad.hasColor() ? quad.getColorIndex() : -1, center));
        }
    }

    private static WorldVertex warp(SourceVertex source, double moduleStart,
                                    double moduleLength, CurveSampler curve,
                                    int lateralTile, int lateralTiles,
                                    int verticalTile, int verticalTiles) {
        double s = moduleStart + source.q * moduleLength;
        Frame frame = curve.sample(s);
        double lateralCell = frame.lateralSpan / lateralTiles;
        double verticalCell = frame.verticalSpan / verticalTiles;
        double lateralOffset = ((lateralTile + 0.5) - lateralTiles * 0.5 + source.lateral) * lateralCell;
        double verticalOffset = ((verticalTile + 0.5) - verticalTiles * 0.5 + source.vertical) * verticalCell;
        Vec3d world = frame.center
                .add(frame.lateral.multiply(lateralOffset))
                .add(frame.vertical.multiply(verticalOffset));
        return new WorldVertex(world, source.u, source.v, s);
    }

    private static Vector3f normal(WorldVertex a, WorldVertex b, WorldVertex c) {
        Vector3f u = new Vector3f((float) (b.world.x - a.world.x),
                (float) (b.world.y - a.world.y), (float) (b.world.z - a.world.z));
        Vector3f v = new Vector3f((float) (c.world.x - a.world.x),
                (float) (c.world.y - a.world.y), (float) (c.world.z - a.world.z));
        u.cross(v);
        return u.lengthSquared() < 1.0E-10f ? null : u.normalize();
    }

    private static Direction dominantDirection(Vector3f normal) {
        float ax=Math.abs(normal.x), ay=Math.abs(normal.y), az=Math.abs(normal.z);
        if (ay>=ax && ay>=az) return normal.y>=0?Direction.UP:Direction.DOWN;
        if (ax>=az) return normal.x>=0?Direction.EAST:Direction.WEST;
        return normal.z>=0?Direction.SOUTH:Direction.NORTH;
    }

    private static Vector3f directionVector(Direction direction) {
        return new Vector3f(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normals,
                             WorldVertex vertex, BlockPos origin, Vector3f normal,
                             int light, int overlay, int red, int green, int blue) {
        consumer.vertex(position,
                        (float) (vertex.world.x - origin.getX()),
                        (float) (vertex.world.y - origin.getY()),
                        (float) (vertex.world.z - origin.getZ()))
                .color(red, green, blue, 255)
                .texture(vertex.u, vertex.v)
                .overlay(overlay).light(light)
                .normal(normals, normal.x, normal.y, normal.z).next();
    }

    private static int maxPacked(int first, int second) {
        int block = Math.max(first & 0xFFFF, second & 0xFFFF);
        int sky = Math.max((first >>> 16) & 0xFFFF, (second >>> 16) & 0xFFFF);
        return block | (sky << 16);
    }

    /** Smooth geometric sampler built from the existing white Model-Block arc. */
    private static final class CurveSampler {
        private final ArcComponentFinder.Component component;
        private final List<ArcComponentFinder.Segment> segments;
        private final List<ArcStationFrames.Station> stations;
        private final double total;
        private final boolean sourceReverse;
        private final boolean lateralUsesWidth;
        private final double lateralSign;
        private final double verticalSign;

        CurveSampler(ArcComponentFinder.Component component, Direction.Axis sourceAxis, boolean sourceReverse) {
            this.component = component;
            this.segments = component.segments();
            List<ArcStationFrames.Station> builtStations = new ArrayList<>(ArcStationFrames.build(component));
            if (!builtStations.isEmpty() && component.startModelBlock() != null
                    && component.world().getBlockEntity(component.startModelBlock()) instanceof ModelBlockEntity model) {
                builtStations.set(0, ArcStationFrames.alignEndpoint(builtStations.get(0), model));
            }
            if (!builtStations.isEmpty() && component.endModelBlock() != null
                    && component.world().getBlockEntity(component.endModelBlock()) instanceof ModelBlockEntity model) {
                int last = builtStations.size() - 1;
                builtStations.set(last, ArcStationFrames.alignEndpoint(builtStations.get(last), model));
            }
            this.stations = List.copyOf(builtStations);
            this.total = component.totalLength();
            this.sourceReverse = sourceReverse;
            Frame mid = rawSample(total * 0.5);
            ArcCrossSectionMapping.Mapping mapping = ArcCrossSectionMapping.resolve(
                    mid.width, mid.radial, mid.widthSpan, mid.radialSpan,
                    preferredInnerDirection(component));
            lateralUsesWidth = mapping.lateralUsesWidth();
            lateralSign = mapping.lateralSign();
            verticalSign = mapping.verticalSign();
        }

        double totalLength() { return total; }

        ArcRibbonBlockEntity ownerAt(double s) {
            return segmentAt(Math.max(0.0, Math.min(total, s))).owner();
        }

        boolean reversesWinding(Direction.Axis sourceAxis, double s) {
            Frame frame = sample(s);
            double target = frame.tangent.crossProduct(frame.lateral).dotProduct(frame.vertical);
            double source = sourceAxis == Direction.Axis.Z ? 1.0 : -1.0;
            if (sourceReverse) source = -source;
            return target * source < 0.0;
        }

        Frame sample(double s) {
            Frame base = rawSample(s);
            Vec3d lateral = (lateralUsesWidth ? base.width : base.radial).multiply(lateralSign);
            Vec3d vertical = (lateralUsesWidth ? base.radial : base.width).multiply(verticalSign);
            double lateralSpan = lateralUsesWidth ? base.widthSpan : base.radialSpan;
            double verticalSpan = lateralUsesWidth ? base.radialSpan : base.widthSpan;
            return new Frame(base.center, base.tangent, lateral, vertical,
                    lateralSpan, verticalSpan, base.width, base.radial,
                    base.widthSpan, base.radialSpan);
        }

        private static Direction preferredInnerDirection(ArcComponentFinder.Component component) {
            BlockPos[] endpoints = {component.startModelBlock(), component.endModelBlock()};
            for (BlockPos endpoint : endpoints) {
                if (endpoint == null) continue;
                var blockEntity = component.world().getBlockEntity(endpoint);
                if (blockEntity instanceof ModelBlockEntity model && model.getInnerArcDirection() != null) {
                    return model.getInnerArcDirection();
                }
            }
            return component.innerFace();
        }

        /**
         * The centerline remains the exact generated polyline, but width/radial/spans come from the
         * shared topology stations.  Both sides of a joint therefore evaluate the identical section;
         * the outer arc can no longer open while the inner arc overlaps.
         */
        private Frame rawSample(double s) {
            if (segments.isEmpty() || stations.size() != segments.size() + 1) {
                return new Frame(Vec3d.ZERO, new Vec3d(1,0,0), new Vec3d(0,0,1), new Vec3d(0,1,0),
                        1,1,new Vec3d(0,0,1),new Vec3d(0,1,0),1,1);
            }
            if (s <= 0.0) return stationFrame(stations.get(0));
            if (s >= total) return stationFrame(stations.get(stations.size()-1));
            int index = segmentIndexAt(s);
            ArcComponentFinder.Segment segment = segments.get(index);
            ArcStationFrames.Station a = stations.get(index);
            ArcStationFrames.Station b = stations.get(index + 1);
            double t = segment.length() < EPS ? 0.0 : (s - segment.s0()) / segment.length();
            t = Math.max(0.0, Math.min(1.0, t));
            Vec3d center = a.center().lerp(b.center(), t);
            Vec3d tangent = normalizedLerp(a.tangent(), b.tangent(), t, segment.c1().subtract(segment.c0()));
            Vec3d width = normalizedLerp(a.width(), b.width(), t, a.width());
            width = orthogonal(width, tangent);
            Vec3d radial = normalizedLerp(a.radial(), b.radial(), t, a.radial());
            radial = orthogonal(radial, tangent);
            radial = radial.subtract(width.multiply(radial.dotProduct(width)));
            if (radial.lengthSquared() < EPS) radial = tangent.crossProduct(width);
            radial = radial.normalize();
            Vec3d expected = a.radial().lerp(b.radial(), t);
            if (expected.lengthSquared() > EPS && radial.dotProduct(expected) < 0.0) radial = radial.multiply(-1.0);
            double widthSpan = lerp(a.widthSpan(), b.widthSpan(), t);
            double radialSpan = lerp(a.radialSpan(), b.radialSpan(), t);
            return new Frame(center,tangent,width,radial,widthSpan,radialSpan,width,radial,widthSpan,radialSpan);
        }

        private static Frame stationFrame(ArcStationFrames.Station station) {
            return new Frame(station.center(),station.tangent(),station.width(),station.radial(),
                    station.widthSpan(),station.radialSpan(),station.width(),station.radial(),
                    station.widthSpan(),station.radialSpan());
        }

        private static Vec3d orthogonal(Vec3d axis, Vec3d tangent) {
            Vec3d value = axis.subtract(tangent.multiply(axis.dotProduct(tangent)));
            if (value.lengthSquared() < EPS) {
                Vec3d fallback = Math.abs(tangent.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
                value = fallback.subtract(tangent.multiply(fallback.dotProduct(tangent)));
            }
            return value.normalize();
        }

        private static Vec3d normalizedLerp(Vec3d first, Vec3d second, double t, Vec3d fallback) {
            Vec3d value = first.lerp(second, t);
            if (value.lengthSquared() < EPS) value = fallback;
            return value.lengthSquared() < EPS ? new Vec3d(1,0,0) : value.normalize();
        }

        private int segmentIndexAt(double s) {
            int low = 0, high = segments.size() - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (segments.get(middle).s0() <= s) low = middle;
                else high = middle - 1;
            }
            return low;
        }

        private ArcComponentFinder.Segment segmentAt(double s) {
            return segments.get(segmentIndexAt(s));
        }

        private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    }

    private record Frame(Vec3d center, Vec3d tangent, Vec3d lateral, Vec3d vertical,
                         double lateralSpan, double verticalSpan,
                         Vec3d width, Vec3d radial, double widthSpan, double radialSpan) {}

    private record PreparedQuad(BakedQuad quad, List<SourceVertex> polygon,
                                boolean qStart, boolean qEnd,
                                boolean lateralMin, boolean lateralMax,
                                boolean verticalMin, boolean verticalMax) {}

    private record SourceVertex(double q, double lateral, double vertical, float u, float v) {
        SourceVertex lerp(SourceVertex other, double t) {
            return new SourceVertex(q + (other.q - q) * t,
                    lateral + (other.lateral - lateral) * t,
                    vertical + (other.vertical - vertical) * t,
                    (float) (u + (other.u - u) * t),
                    (float) (v + (other.v - v) * t));
        }
    }

    private record WorldVertex(Vec3d world, float u, float v, double s) {}
    private record Triangle(WorldVertex a, WorldVertex b, WorldVertex c,
                            Vector3f normal, int tintIndex, Vec3d worldCenter) {}
    private record MeshHandle(BlockState state,
                              Map<BlockPos,Integer> memberRevisions,
                              Map<BlockPos,BlockState> memberStates,
                              Map<BlockPos, List<Triangle>> byOwner,
                              Map<Long,List<BlockPos>> ownersByChunk,
                              BlockPos renderLeader) {
        static MeshHandle empty(BlockState state) {
            return new MeshHandle(state, Map.of(), Map.of(), Map.of(), Map.of(), null);
        }

        boolean matches(ArcRibbonBlockEntity entity) {
            if (entity.getWorld() == null || !state.equals(entity.getSourceState())) return false;
            Integer expectedRevision = memberRevisions.get(entity.getPos());
            if (expectedRevision == null || entity.getRenderRevision() != expectedRevision) return false;
            BlockState expectedState = memberStates.get(entity.getPos());
            return expectedState == null || expectedState.equals(entity.getSourceState());
        }
    }
}
