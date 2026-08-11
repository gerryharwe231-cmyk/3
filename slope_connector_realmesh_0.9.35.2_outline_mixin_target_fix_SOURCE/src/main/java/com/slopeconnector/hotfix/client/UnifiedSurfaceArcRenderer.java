package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.surface.geometry.SegmentChainOrder;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 0.9.23 global arc-surface renderer.
 *
 * <p>The complete connected arc is reconstructed before any UV is produced. Longitudinal faces use
 * one continuous (S,P) atlas: S is corrected accumulated arc length and P is distance around the
 * complete rectangular cross-section. This is materially different from the old per-face S/W or
 * S/N mapping: top, outer side, bottom and inner side now share one perimeter coordinate and cannot
 * restart, mirror or change checker parity at their common edges.</p>
 *
 * <p>Every prism is matched to one ordered centreline segment. Its four start vertices receive the
 * exact same S station and its four end vertices receive the next station. No vertex performs a nearest-segment lookup. Segment order is reconstructed from an exact
 * endpoint graph, so a long or tight arc cannot jump to a spatially-near but non-adjacent segment.</p>
 */
public final class UnifiedSurfaceArcRenderer {
    private static final float EPS = 1.0E-6f;
    private static final float JOIN_EPS = 0.18f;
    private static final float TOPOLOGY_ENDPOINT_EPS = 0.08f;
    private static final int DISCOVERY_RADIUS = 3;
    private static final int MAX_COMPONENT_ENTITIES = 4096;
    private static final int MAX_COMPONENT_SEGMENTS = 131072;
    private static final int MAX_TILE_CELLS_PER_FACE = 4096;
    private static final float REPRESENTATIVE_BIN = 0.125f;
    /** Compatibility marker for the 0.9.23/0.9.28 static contract.
     *  0.9.29 no longer rebuilds the atlas on this TTL; cache invalidation is revision-driven. */
    @Deprecated
    private static final long ATLAS_TTL_TICKS = 20L;

    private static final Map<ArcRibbonBlockEntity, CompiledMesh> MESH_CACHE = new WeakHashMap<>();
    private static final Map<ArcRibbonBlockEntity, CachedAtlas> ATLAS_CACHE = new WeakHashMap<>();

    private UnifiedSurfaceArcRenderer() {}

    public static void renderReplacement(ArcRibbonBlockEntity entity, float tickDelta,
                                         MatrixStack matrices, VertexConsumerProvider consumers,
                                         int light, int overlay) {
        if (entity.getWorld() == null) return;
        ComponentAtlas atlas = atlasFor(entity);
        if (atlas.segments.isEmpty()) return;

        CompiledMesh mesh = MESH_CACHE.get(entity);
        if (mesh == null || mesh.revision != entity.getRenderRevision()
                || mesh.atlasRevision != atlas.revision) {
            mesh = compile(entity, atlas);
            MESH_CACHE.put(entity, mesh);
        }
        if (mesh.triangles.isEmpty()) return;

        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(entity.getSourceState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normal = entry.getNormalMatrix();
        int[] directionalLights = {-1, -1, -1, -1, -1, -1};
        BlockPos blockPos = entity.getPos();

        for (Triangle triangle : mesh.triangles) {
            int lightIndex = triangle.direction.ordinal();
            int packedLight = directionalLights[lightIndex];
            if (packedLight < 0) {
                int sampled = WorldRenderer.getLightmapCoordinates(entity.getWorld(),
                        entity.getSourceState(), blockPos.offset(triangle.direction));
                packedLight = maxPacked(light, sampled);
                directionalLights[lightIndex] = packedLight;
            }
            int color = triangle.material.color();
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            emit(consumer, position, normal, triangle.a, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.b, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.c, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.c, triangle, packedLight, overlay, red, green, blue);
        }
    }

    private static CompiledMesh compile(ArcRibbonBlockEntity entity, ComponentAtlas atlas) {
        List<Triangle> triangles = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (com.slopeconnector.model.ArcPrismTags.isMetadata(prism)) continue;
            PrismAssignment assignment = atlas.assignment(entity, prism);
            if (assignment == null) continue;
            float[] vertices = prism.xyz();
            if (prism.draws(0)) addFace(entity, atlas, assignment, triangles, vertices,
                    new int[]{0, 4, 5, 1}, SurfaceSide.BOTTOM, prism.materialHint());
            if (prism.draws(1)) addFace(entity, atlas, assignment, triangles, vertices,
                    new int[]{3, 2, 6, 7}, SurfaceSide.TOP, prism.materialHint());
            if (prism.draws(2)) addFace(entity, atlas, assignment, triangles, vertices,
                    new int[]{0, 3, 7, 4}, SurfaceSide.LEFT, prism.materialHint());
            if (prism.draws(3)) addFace(entity, atlas, assignment, triangles, vertices,
                    new int[]{1, 5, 6, 2}, SurfaceSide.RIGHT, prism.materialHint());
            if (prism.draws(4)) addFace(entity, atlas, assignment, triangles, vertices,
                    new int[]{0, 1, 2, 3}, SurfaceSide.START_CAP, prism.materialHint());
            if (prism.draws(5)) addFace(entity, atlas, assignment, triangles, vertices,
                    new int[]{4, 7, 6, 5}, SurfaceSide.END_CAP, prism.materialHint());
        }
        // Endpoint SurfaceQuad overlays remain disabled. Endpoint blocks keep native rendering so
        // ordinary blocks placed beside them continue through Minecraft's normal model pipeline.
        return new CompiledMesh(entity.getRenderRevision(), atlas.revision, List.copyOf(triangles));
    }

    private static void addFace(ArcRibbonBlockEntity entity, ComponentAtlas atlas,
                                PrismAssignment assignment, List<Triangle> output,
                                float[] source, int[] ids, SurfaceSide side,
                                byte materialHint) {
        GeometryVertex[] corners = new GeometryVertex[4];
        for (int index = 0; index < 4; index++) {
            int vertexIndex = ids[index];
            int p = vertexIndex * 3;
            float lx = source[p], ly = source[p + 1], lz = source[p + 2];
            double wx = entity.getPos().getX() + lx;
            double wy = entity.getPos().getY() + ly;
            double wz = entity.getPos().getZ() + lz;
            CurveCoordinate coordinate = assignment.coordinate(vertexIndex < 4,
                    new Vec3((float)wx, (float)wy, (float)wz));
            corners[index] = new GeometryVertex(lx, ly, lz, coordinate);
        }

        Vector3f faceNormal = normal(corners[0], corners[1], corners[2]);
        if (faceNormal == null) return;
        GeometryVertex faceCentre = average(corners);
        GeometryVertex prismCentre = prismCentre(entity, source, assignment);
        Vector3f outward = new Vector3f(faceCentre.lx - prismCentre.lx,
                faceCentre.ly - prismCentre.ly, faceCentre.lz - prismCentre.lz);
        if (faceNormal.dot(outward) < 0.0f) {
            GeometryVertex swap = corners[1];
            corners[1] = corners[3];
            corners[3] = swap;
            faceNormal.mul(-1.0f);
        }
        Direction direction = ArcMaterialHelper.dominant(faceNormal.x, faceNormal.y, faceNormal.z);

        ParameterVertex[] parameterized = new ParameterVertex[4];
        for (int index = 0; index < 4; index++) {
            CurveCoordinate coordinate = corners[index].coordinate;
            float a;
            float b;
            if (side.isCap()) {
                a = coordinate.w - atlas.minW;
                b = coordinate.n - atlas.minN;
            } else {
                a = atlas.mapS(coordinate.s);
                b = atlas.perimeter(side, coordinate.w, coordinate.n);
            }
            parameterized[index] = new ParameterVertex(corners[index].lx, corners[index].ly,
                    corners[index].lz, a, b);
        }

        float edgeA = Math.max(distance(corners[0], corners[1]), distance(corners[3], corners[2]));
        float edgeB = Math.max(distance(corners[0], corners[3]), distance(corners[1], corners[2]));
        float aspect = Math.max(edgeA, edgeB) / Math.max(1.0E-4f, Math.min(edgeA, edgeB));
        float area = Math.max(1.0E-4f, edgeA * edgeB);
        ArcMaterialHelper.FaceMaterial material = ArcMaterialHelper.material(
                entity.getSourceState(), direction, entity.getWorld(), entity.getPos(),
                materialHint, aspect, area);

        if (side.isCap()) {
            float station = atlas.mapS(faceCentre.coordinate.s);
            if (station < -EPS || station > atlas.visibleTiles + EPS) return;
            splitTriangleByTiles(output, parameterized[0], parameterized[1], parameterized[2], direction, material);
            splitTriangleByTiles(output, parameterized[0], parameterized[2], parameterized[3], direction, material);
            return;
        }

        List<ParameterVertex> visible = new ArrayList<>(List.of(parameterized));
        visible = clip(visible, true, 0.0f, true);
        visible = clip(visible, true, atlas.visibleTiles, false);
        if (visible.size() < 3) return;
        ParameterVertex origin = visible.get(0);
        for (int index = 1; index < visible.size() - 1; index++) {
            splitTriangleByTiles(output, origin, visible.get(index), visible.get(index + 1), direction, material);
        }
    }

    private static GeometryVertex prismCentre(ArcRibbonBlockEntity entity, float[] source,
                                              PrismAssignment assignment) {
        float lx = 0, ly = 0, lz = 0;
        for (int index = 0; index < 8; index++) {
            lx += source[index * 3];
            ly += source[index * 3 + 1];
            lz += source[index * 3 + 2];
        }
        lx /= 8.0f; ly /= 8.0f; lz /= 8.0f;
        Vec3 world = new Vec3(entity.getPos().getX() + lx,
                entity.getPos().getY() + ly, entity.getPos().getZ() + lz);
        CurveCoordinate coordinate = assignment.coordinate(false, world);
        return new GeometryVertex(lx, ly, lz, coordinate);
    }

    private static void splitTriangleByTiles(List<Triangle> output,
                                             ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                             Direction direction,
                                             ArcMaterialHelper.FaceMaterial material) {
        float minA = Math.min(a.a, Math.min(b.a, c.a));
        float maxA = Math.max(a.a, Math.max(b.a, c.a));
        float minB = Math.min(a.b, Math.min(b.b, c.b));
        float maxB = Math.max(a.b, Math.max(b.b, c.b));
        int firstA = floorTile(minA);
        int lastA = maxA - minA < EPS ? firstA : ceilTile(maxA) - 1;
        int firstB = floorTile(minB);
        int lastB = maxB - minB < EPS ? firstB : ceilTile(maxB) - 1;
        long tileCount = (long)(lastA - firstA + 1) * (long)(lastB - firstB + 1);
        if (tileCount > MAX_TILE_CELLS_PER_FACE) {
            splitLongTriangle(output, a, b, c, direction, material, 0);
            return;
        }

        List<ParameterVertex> original = List.of(a, b, c);
        for (int tileA = firstA; tileA <= lastA; tileA++) {
            for (int tileB = firstB; tileB <= lastB; tileB++) {
                List<ParameterVertex> polygon = new ArrayList<>(original);
                polygon = clip(polygon, true, tileA, true);
                polygon = clip(polygon, true, tileA + 1.0f, false);
                polygon = clip(polygon, false, tileB, true);
                polygon = clip(polygon, false, tileB + 1.0f, false);
                if (polygon.size() < 3) continue;
                ParameterVertex origin = polygon.get(0).localize(tileA, tileB);
                for (int index = 1; index < polygon.size() - 1; index++) {
                    ParameterVertex p1 = polygon.get(index).localize(tileA, tileB);
                    ParameterVertex p2 = polygon.get(index + 1).localize(tileA, tileB);
                    addTriangle(output, origin, p1, p2, direction, material);
                }
            }
        }
    }

    private static void splitLongTriangle(List<Triangle> output,
                                          ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                          Direction direction,
                                          ArcMaterialHelper.FaceMaterial material,
                                          int depth) {
        if (depth >= 14) {
            // Never smear a single texture tile over a giant face. At the depth guard, emit four
            // geometrically small triangles instead of reverting to fractional UVs for the whole face.
            ParameterVertex ab = a.lerp(b, 0.5f);
            ParameterVertex bc = b.lerp(c, 0.5f);
            ParameterVertex ca = c.lerp(a, 0.5f);
            addTriangle(output, a.withLocalFraction(), ab.withLocalFraction(), ca.withLocalFraction(), direction, material);
            addTriangle(output, ab.withLocalFraction(), b.withLocalFraction(), bc.withLocalFraction(), direction, material);
            addTriangle(output, ca.withLocalFraction(), bc.withLocalFraction(), c.withLocalFraction(), direction, material);
            addTriangle(output, ab.withLocalFraction(), bc.withLocalFraction(), ca.withLocalFraction(), direction, material);
            return;
        }
        ParameterVertex ab = a.lerp(b, 0.5f);
        ParameterVertex bc = b.lerp(c, 0.5f);
        ParameterVertex ca = c.lerp(a, 0.5f);
        splitLongTriangleOrTiles(output, a, ab, ca, direction, material, depth + 1);
        splitLongTriangleOrTiles(output, ab, b, bc, direction, material, depth + 1);
        splitLongTriangleOrTiles(output, ca, bc, c, direction, material, depth + 1);
        splitLongTriangleOrTiles(output, ab, bc, ca, direction, material, depth + 1);
    }

    private static void splitLongTriangleOrTiles(List<Triangle> output,
                                                  ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                                  Direction direction,
                                                  ArcMaterialHelper.FaceMaterial material,
                                                  int depth) {
        float rangeA = Math.max(a.a, Math.max(b.a, c.a)) - Math.min(a.a, Math.min(b.a, c.a));
        float rangeB = Math.max(a.b, Math.max(b.b, c.b)) - Math.min(a.b, Math.min(b.b, c.b));
        if (Math.max(rangeA, rangeB) <= 8.0f) {
            splitTriangleByTiles(output, a, b, c, direction, material);
        } else {
            splitLongTriangle(output, a, b, c, direction, material, depth);
        }
    }

    private static List<ParameterVertex> clip(List<ParameterVertex> input,
                                               boolean firstAxis, float boundary,
                                               boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<ParameterVertex> output = new ArrayList<>();
        ParameterVertex previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, firstAxis, boundary, keepGreater);
        for (ParameterVertex current : input) {
            boolean currentInside = inside(current, firstAxis, boundary, keepGreater);
            if (currentInside != previousInside) {
                float previousValue = firstAxis ? previous.a : previous.b;
                float currentValue = firstAxis ? current.a : current.b;
                float denominator = currentValue - previousValue;
                float amount = Math.abs(denominator) < EPS ? 0.0f : (boundary - previousValue) / denominator;
                output.add(previous.lerp(current, clamp01(amount)));
            }
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(ParameterVertex vertex, boolean firstAxis,
                                  float boundary, boolean keepGreater) {
        float value = firstAxis ? vertex.a : vertex.b;
        return keepGreater ? value >= boundary - EPS : value <= boundary + EPS;
    }

    private static void addTriangle(List<Triangle> output,
                                    ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                    Direction direction,
                                    ArcMaterialHelper.FaceMaterial material) {
        Vector3f n = normal(a, b, c);
        if (n == null) return;
        Vector3f expected = new Vector3f(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        if (n.dot(expected) < 0.0f) {
            ParameterVertex swap = b; b = c; c = swap; n.mul(-1.0f);
        }
        output.add(new Triangle(a, b, c, n.x, n.y, n.z,
                ArcMaterialHelper.dominant(n.x, n.y, n.z), material));
    }

    private static ComponentAtlas atlasFor(ArcRibbonBlockEntity entity) {
        CachedAtlas cached = ATLAS_CACHE.get(entity);
        if (cached != null && cached.revision() == entity.getRenderRevision()
                && cached.sourceState().equals(entity.getSourceState())) return cached.atlas();
        ComponentAtlas atlas = ComponentAtlas.build(entity);
        for (ArcRibbonBlockEntity member : atlas.members) {
            ATLAS_CACHE.put(member, new CachedAtlas(member.getRenderRevision(), member.getSourceState(), atlas));
        }
        return atlas;
    }

    private static final class ComponentAtlas {
        final List<ArcRibbonBlockEntity> members;
        final List<AtlasSegment> segments;
        final int revision;
        final Map<BlockPos, Integer> memberRevisions;
        final float startInset;
        final float visibleLength;
        final float correction;
        final int visibleTiles;
        final float minW, maxW, minN, maxN;
        final float widthSpan, thicknessSpan, perimeter;

        private ComponentAtlas(List<ArcRibbonBlockEntity> members,
                               List<AtlasSegment> segments,
                               int revision,
                               Map<BlockPos, Integer> memberRevisions,
                               float startInset, float visibleLength,
                               float correction, int visibleTiles,
                               float minW, float maxW, float minN, float maxN) {
            this.members = members;
            this.segments = segments;
            this.revision = revision;
            this.memberRevisions = memberRevisions;
            this.startInset = startInset;
            this.visibleLength = Math.max(EPS, visibleLength);
            this.correction = correction;
            this.visibleTiles = Math.max(1, visibleTiles);
            this.minW = minW;
            this.maxW = maxW;
            this.minN = minN;
            this.maxN = maxN;
            this.widthSpan = Math.max(EPS, maxW - minW);
            this.thicknessSpan = Math.max(EPS, maxN - minN);
            this.perimeter = 2.0f * (widthSpan + thicknessSpan);
        }

        static ComponentAtlas build(ArcRibbonBlockEntity target) {
            List<ArcRibbonBlockEntity> members = discoverComponent(target);
            List<RawSegment> raw = new ArrayList<>();
            Map<BlockPos, Integer> memberRevisions = new LinkedHashMap<>();
            int revision = 1;
            for (ArcRibbonBlockEntity member : members) {
                memberRevisions.put(member.getPos(), member.getRenderRevision());
                revision = 31 * revision + member.getRenderRevision();
                revision = 31 * revision + member.getPos().hashCode();
                extractSegments(member, raw);
                if (raw.size() >= MAX_COMPONENT_SEGMENTS) break;
            }
            List<AtlasSegment> ordered = orderSegments(raw);
            if (ordered.isEmpty()) {
                return new ComponentAtlas(members, List.of(), revision, Map.copyOf(memberRevisions),
                        0, 1, 0, 1, -0.5f, 0.5f, -0.5f, 0.5f);
            }

            ComponentAtlas temporary = new ComponentAtlas(members, ordered, revision, Map.copyOf(memberRevisions),
                    0, 1, 0, 1,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
            float minW = Float.POSITIVE_INFINITY, maxW = Float.NEGATIVE_INFINITY;
            float minN = Float.POSITIVE_INFINITY, maxN = Float.NEGATIVE_INFINITY;
            for (ArcRibbonBlockEntity member : members) {
                for (ArcRibbonBlockEntity.Prism prism : member.getPrisms()) {
                    if (com.slopeconnector.model.ArcPrismTags.isMetadata(prism)) continue;
                    PrismAssignment assignment = temporary.assignment(member, prism);
                    if (assignment == null) continue;
                    float[] xyz = prism.xyz();
                    for (int vertex = 0; vertex < 8; vertex++) {
                        int p = vertex * 3;
                        Vec3 world = new Vec3(member.getPos().getX() + xyz[p],
                                member.getPos().getY() + xyz[p + 1],
                                member.getPos().getZ() + xyz[p + 2]);
                        CurveCoordinate coordinate = assignment.coordinate(vertex < 4, world);
                        minW = Math.min(minW, coordinate.w); maxW = Math.max(maxW, coordinate.w);
                        minN = Math.min(minN, coordinate.n); maxN = Math.max(maxN, coordinate.n);
                    }
                }
            }
            if (!Float.isFinite(minW) || !Float.isFinite(maxW)) { minW = -0.5f; maxW = 0.5f; }
            if (!Float.isFinite(minN) || !Float.isFinite(maxN)) { minN = -0.5f; maxN = 0.5f; }

            AtlasSegment first = ordered.get(0);
            AtlasSegment last = ordered.get(ordered.size() - 1);
            float totalLength = last.s0 + last.length;
            float startInset = gridInset(first.c0, first.tangent);
            float endInset = gridInset(last.c1, last.tangent.multiply(-1.0f));
            float visibleLength = Math.max(EPS, totalLength - startInset - endInset);
            int visibleTiles = Math.max(1, Math.round(visibleLength));
            float correction = visibleTiles - visibleLength;

            revision = 31 * revision + ordered.size();
            revision = 31 * revision + Float.floatToIntBits(totalLength);
            revision = 31 * revision + Float.floatToIntBits(minW);
            revision = 31 * revision + Float.floatToIntBits(maxW);
            revision = 31 * revision + Float.floatToIntBits(minN);
            revision = 31 * revision + Float.floatToIntBits(maxN);
            return new ComponentAtlas(members, ordered, revision, Map.copyOf(memberRevisions),
                    startInset, visibleLength, correction, visibleTiles,
                    minW, maxW, minN, maxN);
        }

        float mapS(float s) {
            float raw = s - startInset;
            float t = clamp01(raw / visibleLength);
            float smooth = t * t * (3.0f - 2.0f * t);
            return raw + correction * smooth;
        }

        float perimeter(SurfaceSide side, float w, float n) {
            float x = clamp(w - minW, 0.0f, widthSpan);
            float y = clamp(n - minN, 0.0f, thicknessSpan);
            return switch (side) {
                case TOP -> x;
                case RIGHT -> widthSpan + (thicknessSpan - y);
                case BOTTOM -> widthSpan + thicknessSpan + (widthSpan - x);
                case LEFT -> 2.0f * widthSpan + thicknessSpan + y;
                default -> 0.0f;
            };
        }

        PrismAssignment assignment(ArcRibbonBlockEntity entity, ArcRibbonBlockEntity.Prism prism) {
            float[] xyz = prism.xyz();
            Vec3 offset = new Vec3(entity.getPos().getX(), entity.getPos().getY(), entity.getPos().getZ());
            Vec3 c0 = average(xyz, 0, 4).add(offset);
            Vec3 c1 = average(xyz, 4, 8).add(offset);
            AtlasSegment bestSegment = null;
            boolean reversed = false;
            float best = Float.POSITIVE_INFINITY;
            for (AtlasSegment segment : segments) {
                float direct = c0.distanceSquared(segment.c0) + c1.distanceSquared(segment.c1);
                if (direct < best) { best = direct; bestSegment = segment; reversed = false; }
                float reverse = c0.distanceSquared(segment.c1) + c1.distanceSquared(segment.c0);
                if (reverse < best) { best = reverse; bestSegment = segment; reversed = true; }
            }
            return bestSegment == null ? null : new PrismAssignment(bestSegment, reversed);
        }


    }

    private static float gridInset(Vec3 point, Vec3 direction) {
        float ax = Math.abs(direction.x), ay = Math.abs(direction.y), az = Math.abs(direction.z);
        float coordinate;
        float component;
        if (ax >= ay && ax >= az) { coordinate = point.x; component = direction.x; }
        else if (ay >= az) { coordinate = point.y; component = direction.y; }
        else { coordinate = point.z; component = direction.z; }
        if (Math.abs(component) < EPS) return 0.0f;
        float nearestInteger = Math.round(coordinate);
        if (Math.abs(coordinate - nearestInteger) < 1.0E-4f) return 0.0f;
        double boundary = component > 0.0f
                ? Math.ceil(coordinate - 1.0E-7)
                : Math.floor(coordinate + 1.0E-7);
        float distance = (float)((boundary - coordinate) / component);
        return distance >= -EPS && distance <= 0.35f ? Math.max(0.0f, distance) : 0.0f;
    }

    private static List<ArcRibbonBlockEntity> discoverComponent(ArcRibbonBlockEntity target) {
        if (target.getWorld() == null) return List.of(target);
        Set<ArcRibbonBlockEntity> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ArcRibbonBlockEntity> queue = new ArrayDeque<>();
        Map<ArcRibbonBlockEntity, List<RawSegment>> segmentCache = new IdentityHashMap<>();
        accepted.add(target); queue.add(target);
        while (!queue.isEmpty() && accepted.size() < MAX_COMPONENT_ENTITIES) {
            ArcRibbonBlockEntity current = queue.removeFirst();
            List<RawSegment> currentSegments = segmentCache.computeIfAbsent(current, UnifiedSurfaceArcRenderer::segmentsOf);
            BlockPos origin = current.getPos();
            for (int dx = -DISCOVERY_RADIUS; dx <= DISCOVERY_RADIUS; dx++) {
                for (int dy = -DISCOVERY_RADIUS; dy <= DISCOVERY_RADIUS; dy++) {
                    for (int dz = -DISCOVERY_RADIUS; dz <= DISCOVERY_RADIUS; dz++) {
                        BlockEntity blockEntity = target.getWorld().getBlockEntity(origin.add(dx, dy, dz));
                        if (!(blockEntity instanceof ArcRibbonBlockEntity candidate)) continue;
                        if (accepted.contains(candidate)) continue;
                        if (!candidate.getSourceState().equals(target.getSourceState())) continue;
                        List<RawSegment> candidateSegments = segmentCache.computeIfAbsent(candidate, UnifiedSurfaceArcRenderer::segmentsOf);
                        if (connected(currentSegments, candidateSegments)) {
                            accepted.add(candidate); queue.add(candidate);
                        }
                    }
                }
            }
        }
        List<ArcRibbonBlockEntity> result = new ArrayList<>(accepted);
        result.sort(Comparator.comparing(ArcRibbonBlockEntity::getPos, UnifiedSurfaceArcRenderer::comparePos));
        return List.copyOf(result);
    }

    private static boolean connected(List<RawSegment> aSegments, List<RawSegment> bSegments) {
        if (aSegments.isEmpty() || bSegments.isEmpty()) return false;
        float limitSquared = TOPOLOGY_ENDPOINT_EPS * TOPOLOGY_ENDPOINT_EPS;
        for (RawSegment first : aSegments) {
            for (RawSegment second : bSegments) {
                if (first.c0.distanceSquared(second.c0) <= limitSquared
                        || first.c0.distanceSquared(second.c1) <= limitSquared
                        || first.c1.distanceSquared(second.c0) <= limitSquared
                        || first.c1.distanceSquared(second.c1) <= limitSquared) return true;
            }
        }
        return false;
    }

    private static List<RawSegment> segmentsOf(ArcRibbonBlockEntity entity) {
        List<RawSegment> collected = new ArrayList<>();
        extractSegments(entity, collected);
        return collected;
    }

    private static void extractSegments(ArcRibbonBlockEntity entity, List<RawSegment> output) {
        float ox = entity.getPos().getX(), oy = entity.getPos().getY(), oz = entity.getPos().getZ();
        List<RawSegment> candidates = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (com.slopeconnector.model.ArcPrismTags.isMetadata(prism)) continue;
            float[] v = prism.xyz();
            Vec3 c0 = average(v, 0, 4).add(new Vec3(ox, oy, oz));
            Vec3 c1 = average(v, 4, 8).add(new Vec3(ox, oy, oz));
            if (c0.distanceSquared(c1) < EPS * EPS) continue;
            Vec3 width0 = point(v, 1).subtract(point(v, 0))
                    .add(point(v, 2).subtract(point(v, 3))).normalizeOr(new Vec3(0, 0, 1));
            Vec3 width1 = point(v, 5).subtract(point(v, 4))
                    .add(point(v, 6).subtract(point(v, 7))).normalizeOr(width0);
            Vec3 radial0 = point(v, 3).subtract(point(v, 0))
                    .add(point(v, 2).subtract(point(v, 1))).normalizeOr(new Vec3(0, 1, 0));
            Vec3 radial1 = point(v, 7).subtract(point(v, 4))
                    .add(point(v, 6).subtract(point(v, 5))).normalizeOr(radial0);
            candidates.add(new RawSegment(c0, c1, width0, width1, radial0, radial1));
            if (candidates.size() >= MAX_COMPONENT_SEGMENTS) break;
        }
        output.addAll(representativeSegments(candidates));
    }

    private static List<RawSegment> representativeSegments(List<RawSegment> candidates) {
        if (candidates.size() <= 1) return candidates;
        Vec3 tangent = averageTangent(candidates);
        if (tangent.length() < EPS) return candidates;

        Map<Long, List<RawSegment>> groups = new LinkedHashMap<>();
        for (RawSegment candidate : candidates) {
            long key = representativeKey(candidate, tangent);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        if (groups.size() == candidates.size()) {
            candidates.sort(Comparator.comparingDouble(segment -> midpoint(segment).dot(tangent)));
            return candidates;
        }

        List<RawSegment> chosen = new ArrayList<>(groups.size());
        for (List<RawSegment> group : groups.values()) {
            Vec3 averageMidpoint = new Vec3(0, 0, 0);
            for (RawSegment candidate : group) averageMidpoint = averageMidpoint.add(midpoint(candidate));
            averageMidpoint = averageMidpoint.multiply(1.0f / group.size());

            RawSegment best = group.get(0);
            float bestDistance = midpoint(best).distanceSquared(averageMidpoint);
            float bestAlignment = alignment(best, tangent);
            for (int index = 1; index < group.size(); index++) {
                RawSegment candidate = group.get(index);
                float distance = midpoint(candidate).distanceSquared(averageMidpoint);
                float alignment = alignment(candidate, tangent);
                if (distance + 1.0E-6f < bestDistance
                        || (Math.abs(distance - bestDistance) <= 1.0E-6f && alignment > bestAlignment)) {
                    best = candidate;
                    bestDistance = distance;
                    bestAlignment = alignment;
                }
            }
            chosen.add(best);
        }
        chosen.sort(Comparator.comparingDouble(segment -> midpoint(segment).dot(tangent)));
        return chosen;
    }

    private static List<AtlasSegment> orderSegments(List<RawSegment> raw) {
        if (raw.isEmpty()) return List.of();
        List<SegmentChainOrder.Edge<RawSegment>> edges = new ArrayList<>(raw.size());
        for (RawSegment segment : raw) {
            edges.add(new SegmentChainOrder.Edge<>(chainPoint(segment.c0), chainPoint(segment.c1), segment));
        }
        List<SegmentChainOrder.Oriented<RawSegment>> chain = SegmentChainOrder.order(
                edges, TOPOLOGY_ENDPOINT_EPS);
        if (chain.isEmpty()) return List.of();

        List<AtlasSegment> result = new ArrayList<>(chain.size());
        Vec3 previousWidth = null, previousRadial = null;
        float cumulative = 0.0f;
        for (SegmentChainOrder.Oriented<RawSegment> ordered : chain) {
            RawSegment oriented = ordered.reversed() ? ordered.value().reversed() : ordered.value();
            Vec3 width0 = oriented.width0, width1 = oriented.width1;
            Vec3 radial0 = oriented.radial0, radial1 = oriented.radial1;
            if (previousWidth != null && previousWidth.dot(width0) < 0.0f) {
                width0 = width0.multiply(-1.0f); width1 = width1.multiply(-1.0f);
            }
            if (previousRadial != null && previousRadial.dot(radial0) < 0.0f) {
                radial0 = radial0.multiply(-1.0f); radial1 = radial1.multiply(-1.0f);
            }
            Vec3 c0 = new Vec3((float) ordered.start().x(), (float) ordered.start().y(), (float) ordered.start().z());
            Vec3 c1 = new Vec3((float) ordered.end().x(), (float) ordered.end().y(), (float) ordered.end().z());
            Vec3 delta = c1.subtract(c0);
            float length = delta.length();
            if (length < EPS) continue;
            result.add(new AtlasSegment(c0, c1,
                    delta.multiply(1.0f / length), width0, width1, radial0, radial1,
                    length, cumulative));
            cumulative += length;
            previousWidth = width1; previousRadial = radial1;
        }
        return List.copyOf(result);
    }

    private static SegmentChainOrder.Point chainPoint(Vec3 point) {
        return new SegmentChainOrder.Point(point.x, point.y, point.z);
    }

    private static Vec3 averageTangent(List<RawSegment> segments) {
        Vec3 tangent = new Vec3(0, 0, 0);
        for (RawSegment segment : segments) {
            Vec3 delta = segment.c1.subtract(segment.c0);
            float length = delta.length();
            if (length > EPS) tangent = tangent.add(delta.multiply(1.0f / length));
        }
        if (tangent.length() < EPS && !segments.isEmpty()) {
            tangent = segments.get(0).c1.subtract(segments.get(0).c0);
        }
        return tangent.normalizeOr(new Vec3(0, 0, 0));
    }

    private static long representativeKey(RawSegment segment, Vec3 tangent) {
        long start = Math.round(segment.c0.dot(tangent) / REPRESENTATIVE_BIN);
        long end = Math.round(segment.c1.dot(tangent) / REPRESENTATIVE_BIN);
        return (start << 32) ^ (end & 0xffffffffL);
    }

    private static Vec3 midpoint(RawSegment segment) {
        return segment.c0.add(segment.c1).multiply(0.5f);
    }

    private static float alignment(RawSegment segment, Vec3 tangent) {
        Vec3 delta = segment.c1.subtract(segment.c0);
        float length = delta.length();
        return length < EPS ? -1.0f : Math.abs(delta.multiply(1.0f / length).dot(tangent));
    }

    private static Vec3 chooseStart(List<RawSegment> segments) {
        List<Vec3> endpoints = new ArrayList<>(segments.size() * 2);
        for (RawSegment segment : segments) { endpoints.add(segment.c0); endpoints.add(segment.c1); }
        Vec3 bestOpen = null, bestAny = null;
        for (int index = 0; index < endpoints.size(); index++) {
            Vec3 point = endpoints.get(index);
            if (bestAny == null || compare(point, bestAny) < 0) bestAny = point;
            int neighbors = 0;
            for (int other = 0; other < endpoints.size(); other++) {
                if (other != index && point.distanceSquared(endpoints.get(other)) <= JOIN_EPS * JOIN_EPS) neighbors++;
            }
            if (neighbors == 0 && (bestOpen == null || compare(point, bestOpen) < 0)) bestOpen = point;
        }
        return bestOpen == null ? bestAny : bestOpen;
    }

    private enum SurfaceSide {
        BOTTOM, TOP, LEFT, RIGHT, START_CAP, END_CAP;
        boolean isCap() { return this == START_CAP || this == END_CAP; }
    }

    private static Vector3f normal(GeometryVertex a, GeometryVertex b, GeometryVertex c) {
        Vector3f first = new Vector3f(b.lx - a.lx, b.ly - a.ly, b.lz - a.lz);
        Vector3f second = new Vector3f(c.lx - a.lx, c.ly - a.ly, c.lz - a.lz);
        Vector3f result = first.cross(second);
        return result.lengthSquared() < 1.0E-10f ? null : result.normalize();
    }

    private static Vector3f normal(ParameterVertex a, ParameterVertex b, ParameterVertex c) {
        Vector3f first = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        Vector3f second = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);
        Vector3f result = first.cross(second);
        return result.lengthSquared() < 1.0E-10f ? null : result.normalize();
    }

    private static GeometryVertex average(GeometryVertex[] vertices) {
        float lx = 0, ly = 0, lz = 0;
        float s = 0, w = 0, n = 0;
        for (GeometryVertex vertex : vertices) {
            lx += vertex.lx; ly += vertex.ly; lz += vertex.lz;
            s += vertex.coordinate.s; w += vertex.coordinate.w; n += vertex.coordinate.n;
        }
        float scale = 1.0f / vertices.length;
        return new GeometryVertex(lx * scale, ly * scale, lz * scale,
                new CurveCoordinate(s * scale, w * scale, n * scale));
    }

    private static float distance(GeometryVertex a, GeometryVertex b) {
        float x = b.lx - a.lx, y = b.ly - a.ly, z = b.lz - a.lz;
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normal,
                             ParameterVertex vertex, Triangle triangle,
                             int light, int overlay, int red, int green, int blue) {
        consumer.vertex(position, vertex.x, vertex.y, vertex.z)
                .color(red, green, blue, 255)
                .texture(triangle.material.u(clamp01(vertex.a), clamp01(vertex.b)),
                        triangle.material.v(clamp01(vertex.a), clamp01(vertex.b)))
                .overlay(overlay).light(light)
                .normal(normal, triangle.nx, triangle.ny, triangle.nz).next();
    }

    private static int floorTile(float value) { return (int)Math.floor(value + EPS); }
    private static int ceilTile(float value) { return (int)Math.ceil(value - EPS); }
    private static float clamp01(float value) { return clamp(value, 0.0f, 1.0f); }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static int maxPacked(int a, int b) {
        int block = Math.max(a & 0xFFFF, b & 0xFFFF);
        int sky = Math.max((a >>> 16) & 0xFFFF, (b >>> 16) & 0xFFFF);
        return block | (sky << 16);
    }
    private static int comparePos(BlockPos a, BlockPos b) {
        int y = Integer.compare(a.getY(), b.getY());
        if (y != 0) return y;
        int x = Integer.compare(a.getX(), b.getX());
        return x != 0 ? x : Integer.compare(a.getZ(), b.getZ());
    }
    private static int compare(Vec3 a, Vec3 b) {
        int y = Float.compare(a.y, b.y);
        if (y != 0) return y;
        int x = Float.compare(a.x, b.x);
        return x != 0 ? x : Float.compare(a.z, b.z);
    }
    private static Vec3 point(float[] data, int index) {
        return new Vec3(data[index * 3], data[index * 3 + 1], data[index * 3 + 2]);
    }
    private static Vec3 average(float[] data, int from, int to) {
        Vec3 result = new Vec3(0, 0, 0);
        for (int index = from; index < to; index++) result = result.add(point(data, index));
        return result.multiply(1.0f / (to - from));
    }

    private record GeometryVertex(float lx, float ly, float lz, CurveCoordinate coordinate) {}
    private record ParameterVertex(float x, float y, float z, float a, float b) {
        ParameterVertex lerp(ParameterVertex other, float amount) {
            return new ParameterVertex(x + (other.x - x) * amount,
                    y + (other.y - y) * amount,
                    z + (other.z - z) * amount,
                    a + (other.a - a) * amount,
                    b + (other.b - b) * amount);
        }
        ParameterVertex localize(int tileA, int tileB) {
            return new ParameterVertex(x, y, z, clamp01(a - tileA), clamp01(b - tileB));
        }
        ParameterVertex withLocalFraction() {
            return new ParameterVertex(x, y, z,
                    a - (float)Math.floor(a), b - (float)Math.floor(b));
        }
    }
    private record Triangle(ParameterVertex a, ParameterVertex b, ParameterVertex c,
                            float nx, float ny, float nz,
                            Direction direction, ArcMaterialHelper.FaceMaterial material) {}
    private record CompiledMesh(int revision, int atlasRevision, List<Triangle> triangles) {}
    private record CachedAtlas(int revision, net.minecraft.block.BlockState sourceState, ComponentAtlas atlas) {}
    private record CurveCoordinate(float s, float w, float n) {}
    private record RawSegment(Vec3 c0, Vec3 c1,
                              Vec3 width0, Vec3 width1,
                              Vec3 radial0, Vec3 radial1) {
        RawSegment reversed() { return new RawSegment(c1, c0, width1, width0, radial1, radial0); }
    }
    private record AtlasSegment(Vec3 c0, Vec3 c1, Vec3 tangent,
                                Vec3 width0, Vec3 width1,
                                Vec3 radial0, Vec3 radial1,
                                float length, float s0) {}
    private record PrismAssignment(AtlasSegment segment, boolean reversed) {
        CurveCoordinate coordinate(boolean firstGroup, Vec3 point) {
            boolean atStart = firstGroup ^ reversed;
            Vec3 centre = atStart ? segment.c0 : segment.c1;
            Vec3 width = atStart ? segment.width0 : segment.width1;
            Vec3 radial = atStart ? segment.radial0 : segment.radial1;
            float s = atStart ? segment.s0 : segment.s0 + segment.length;
            Vec3 offset = point.subtract(centre);
            return new CurveCoordinate(s, offset.dot(width), offset.dot(radial));
        }
    }
    private record Vec3(float x, float y, float z) {
        Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
        Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        Vec3 multiply(float amount) { return new Vec3(x * amount, y * amount, z * amount); }
        float dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        float length() { return (float)Math.sqrt(x * x + y * y + z * z); }
        float distanceSquared(Vec3 other) {
            float dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
        Vec3 normalizeOr(Vec3 fallback) {
            float length = length(); return length < EPS ? fallback : multiply(1.0f / length);
        }
    }
}
