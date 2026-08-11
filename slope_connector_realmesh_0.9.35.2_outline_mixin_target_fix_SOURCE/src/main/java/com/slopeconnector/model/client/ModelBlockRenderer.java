package com.slopeconnector.model.client;

import com.slopeconnector.model.ModelBlockEntity;
import com.slopeconnector.model.ModelStateResolver;
import com.slopeconnector.surface.ConnectionStateHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
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
import java.util.List;

/**
 * Renders a skinned ModelBlock endpoint with the same direct BakedQuad/light strategy used by the
 * curved middle mesh.  The invisible holder itself is never allowed to darken or cull the model.
 *
 * For ordinary non-directional blocks (the checker test cube is the important example) the source
 * module is treated as X-running and is geometrically rotated so +X points toward the arc.  UVs stay
 * attached to the source vertices, therefore a Z-running endpoint receives the required 90 degree
 * texture rotation instead of repeating the world-aligned cube texture at the terminal block.
 */
public final class ModelBlockRenderer implements BlockEntityRenderer<ModelBlockEntity> {
    private static final double EPS = 1.0E-9;

    public ModelBlockRenderer(BlockEntityRendererFactory.Context context) {}

    @Override
    public void render(ModelBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int fallbackLight, int overlay) {
        if (entity.getWorld() == null) return;
        BlockState holderState = entity.getWorld().getBlockState(entity.getPos());
        // Only the two internal ModelEndpointBlocks own this renderer.  Public ModelBlocks are true
        // vanilla-style Blocks and never enter the BlockEntity render path.
        if (holderState.getBlock() != com.slopeconnector.model.ModelSystemMod.MODEL_ENDPOINT_BLOCK) return;
        MinecraftClient client = MinecraftClient.getInstance();
        BlockState state = entity.isSkinned() ? entity.getDisplayState()
                : com.slopeconnector.model.ModelSystemMod.MODEL_BLOCK.getDefaultState();
        BakedModel model;
        List<BakedQuad> quads;
        try {
            model = client.getBlockRenderManager().getModel(state);
            quads = collect(model, state);
        } catch (RuntimeException error) {
            state = Blocks.WHITE_CONCRETE.getDefaultState();
            model = client.getBlockRenderManager().getModel(state);
            quads = collect(model, state);
        }
        if (quads.isEmpty()) return;

        boolean exactCapturedShape = entity.isSkinned()
                && ModelStateResolver.isStairOrSlab(entity.getCapturedState());
        boolean rotateGeometry = !entity.isSkinned() || exactCapturedShape
                || !ModelStateResolver.hasExplicitOrientation(entity.getCapturedState());
        Direction longitudinal = endpointLongitudinal(entity);
        // The entire arc chooses ONE cross-section mapping in ArcEndpointMetadataBinder.  Never
        // resolve this again at an endpoint: a local re-resolve can flip width/radial signs at the
        // terminal block and is the root cause of "middle faces one way, endpoint the opposite".
        Vec3d widthAxis = entity.getEndpointLateralAxis();
        Vec3d radialAxis = entity.getEndpointVerticalAxis();
        double widthSpan = Math.max(1.0, entity.getEndpointLateralSpan());
        double radialSpan = Math.max(1.0, entity.getEndpointVerticalSpan());
        Basis basis = rotateGeometry
                ? basis(longitudinal, radialAxis, widthAxis) : Basis.IDENTITY;
        RenderLayer layer = RenderLayers.getBlockLayer(state);
        VertexConsumer consumer = consumers.getBuffer(layer);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos blockPos = entity.getPos();
        int widthTiles = Math.max(1, Math.min(32, (int)Math.round(widthSpan)));
        int radialTiles = Math.max(1, Math.min(32, (int)Math.round(radialSpan)));
        double widthCell = widthSpan / widthTiles;
        double radialCell = radialSpan / radialTiles;
        boolean cullInternalTileFaces = ModelStateResolver.canCullInternalTileFaces(state);
        boolean connectedProfile = ConnectionStateHelper.isSupported(entity.getCapturedState());
        Direction arcFacing = entity.getArcDirection();
        java.util.EnumSet<Direction> connectedCaps = connectedProfile
                ? connectedCapDirections(entity, arcFacing) : java.util.EnumSet.noneOf(Direction.class);

        for (int wi=0;wi<widthTiles;wi++) for (int ri=0;ri<radialTiles;ri++) {
            double wo=((wi+0.5)-widthTiles*0.5)*widthCell;
            double ro=((ri+0.5)-radialTiles*0.5)*radialCell;
            Vec3d tileOffset=widthAxis.multiply(wo).add(radialAxis.multiply(ro));
            for (BakedQuad quad : quads) {
                Vertex[] baseVertices = decode(quad, basis, Vec3d.ZERO);
                if (baseVertices == null) continue;
                // Connected endpoint models touch the first/last curved module on the face pointing
                // INTO the arc.  Vanilla would hide that touching cap; our BER must do it explicitly.
                // Leaving it behind is the one-sided paper-thin plate visible only from one camera side.
                if (connectedProfile && shouldCullConnectedCap(quad, baseVertices, connectedCaps)) continue;
                // Expanded endpoints are real one-block tiles.  Cull only faces shared by two
                // neighbouring endpoint tiles; otherwise identical coplanar BakedQuads z-fight.
                if (cullInternalTileFaces) {
                    if (isBoundaryFace(baseVertices, widthAxis, -0.5) && wi > 0) continue;
                    if (isBoundaryFace(baseVertices, widthAxis, 0.5) && wi + 1 < widthTiles) continue;
                    if (isBoundaryFace(baseVertices, radialAxis, -0.5) && ri > 0) continue;
                    if (isBoundaryFace(baseVertices, radialAxis, 0.5) && ri + 1 < radialTiles) continue;
                }
                Vertex[] vertices = offset(baseVertices, tileOffset);
                Vector3f normal = faceNormal(vertices);
                if (normal == null) continue;

                int tint = 0xFFFFFF;
                if (quad.hasColor()) {
                    int sampled = client.getBlockColors().getColor(state, entity.getWorld(), blockPos, quad.getColorIndex());
                    if (sampled != -1) tint = sampled & 0xFFFFFF;
                }
                Vec3d quadCenter = new Vec3d(
                        entity.getPos().getX() + (vertices[0].pos.x + vertices[1].pos.x + vertices[2].pos.x + vertices[3].pos.x) * 0.25,
                        entity.getPos().getY() + (vertices[0].pos.y + vertices[1].pos.y + vertices[2].pos.y + vertices[3].pos.y) * 0.25,
                        entity.getPos().getZ() + (vertices[0].pos.z + vertices[1].pos.z + vertices[2].pos.z + vertices[3].pos.z) * 0.25);
                int packedLight = ModelRenderLighting.sample(
                        entity.getWorld(), state, quadCenter, normal, fallbackLight);
                int red = (tint >> 16) & 255;
                int green = (tint >> 8) & 255;
                int blue = tint & 255;
                for (Vertex vertex : vertices) {
                    emit(consumer, position, normalMatrix, vertex, normal,
                            packedLight, overlay, red, green, blue);
                }
            }
        }
    }

    private static List<BakedQuad> collect(BakedModel model, BlockState state) {
        List<BakedQuad> out = new ArrayList<>();
        long seed = state.getRenderingSeed(BlockPos.ORIGIN);
        for (Direction face : Direction.values()) {
            out.addAll(model.getQuads(state, face, Random.create(seed + face.ordinal())));
        }
        out.addAll(model.getQuads(state, null, Random.create(seed + 91L)));
        return out;
    }

    private static Vertex[] decode(BakedQuad quad, Basis basis, Vec3d tileOffset) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        if (stride < 6) return null;
        Vertex[] out = new Vertex[4];
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            Vec3d local = new Vec3d(
                    Float.intBitsToFloat(data[base]),
                    Float.intBitsToFloat(data[base + 1]),
                    Float.intBitsToFloat(data[base + 2]));
            Vec3d transformed = basis.transform(local).add(tileOffset);
            float u = Float.intBitsToFloat(data[base + 4]);
            float v = Float.intBitsToFloat(data[base + 5]);
            // UVs stay attached to their source vertices.  The endpoint basis/state performs the
            // only rotation, so there is no independent 90-degree texture hack that can double-turn
            // the terminal face.
            out[i] = new Vertex(transformed, u, v);
        }
        if (basis.reversesWinding()) {
            Vertex swap = out[1]; out[1] = out[3]; out[3] = swap;
        }
        return out;
    }

    private static java.util.EnumSet<Direction> connectedCapDirections(ModelBlockEntity entity,
                                                                         Direction arcFacing) {
        java.util.EnumSet<Direction> directions = java.util.EnumSet.noneOf(Direction.class);
        if (arcFacing != null) directions.add(arcFacing); // custom middle arc touches this side.
        if (entity.getWorld() == null) return directions;
        BlockState captured = entity.getCapturedState();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (direction == arcFacing) continue;
            BlockState neighbor = ConnectionStateHelper.representedNeighbor(
                    entity.getWorld(), entity.getPos().offset(direction));
            if (ConnectionStateHelper.sameFamily(captured, neighbor)) directions.add(direction);
        }
        return directions;
    }

    private static boolean shouldCullConnectedCap(BakedQuad quad, Vertex[] vertices,
                                                   java.util.EnumSet<Direction> connectedDirections) {
        if (vertices == null || connectedDirections.isEmpty()) return false;
        Vector3f normal = faceNormal(vertices);
        if (normal == null) return false;
        Direction normalDirection = dominant(normal);
        for (Direction direction : connectedDirections) {
            if (isDirectionalBoundaryFace(vertices, direction)) return true;
            // Modded pane/fence models often inset an arm cap to 1/16 or 2/16 instead of putting
            // vertices exactly at 0/1.  Cull only an axis-facing quad near the connected boundary;
            // central posts and lateral bar faces are left untouched.
            if (normalDirection != direction && normalDirection != direction.getOpposite()) continue;
            double target = (direction == Direction.EAST || direction == Direction.UP || direction == Direction.SOUTH)
                    ? 1.0 : 0.0;
            double average = 0.0;
            for (Vertex vertex : vertices) {
                average += switch (direction.getAxis()) {
                    case X -> vertex.pos.x;
                    case Y -> vertex.pos.y;
                    case Z -> vertex.pos.z;
                };
            }
            average /= vertices.length;
            if (Math.abs(average - target) <= 0.126) return true;
            if (quad.getFace() == direction && Math.abs(average - target) <= 0.20) return true;
        }
        return false;
    }

    private static boolean isDirectionalBoundaryFace(Vertex[] vertices, Direction direction) {
        if (vertices == null || vertices.length == 0 || direction == null) return false;
        double target = (direction == Direction.EAST || direction == Direction.UP || direction == Direction.SOUTH)
                ? 1.0 : 0.0;
        for (Vertex vertex : vertices) {
            double coordinate = switch (direction.getAxis()) {
                case X -> vertex.pos.x;
                case Y -> vertex.pos.y;
                case Z -> vertex.pos.z;
            };
            if (Math.abs(coordinate - target) > 1.0E-5) return false;
        }
        return true;
    }

    private static boolean isBoundaryFace(Vertex[] vertices, Vec3d axis, double boundary) {
        Vec3d unit = axis.lengthSquared() < EPS ? axis : axis.normalize();
        if (unit.lengthSquared() < EPS) return false;
        for (Vertex vertex : vertices) {
            Vec3d relative = vertex.pos.subtract(new Vec3d(0.5,0.5,0.5));
            if (Math.abs(relative.dotProduct(unit) - boundary) > 1.0E-5) return false;
        }
        return true;
    }

    private static Vertex[] offset(Vertex[] input, Vec3d amount) {
        if (amount.lengthSquared() < EPS) return input;
        Vertex[] out = new Vertex[input.length];
        for (int i=0;i<input.length;i++) {
            out[i] = new Vertex(input[i].pos.add(amount), input[i].u, input[i].v);
        }
        return out;
    }

    private static Vector3f faceNormal(Vertex[] v) {
        Vector3f a = vec(v[0].pos), b = vec(v[1].pos), c = vec(v[2].pos);
        Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        if (normal.lengthSquared() < 1.0E-8f) return null;
        return normal.normalize();
    }


    private static Direction dominant(Vector3f normal) {
        float ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        if (ay >= ax && ay >= az) return normal.y >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return normal.x >= 0 ? Direction.EAST : Direction.WEST;
        return normal.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Source module direction follows the ordered arc: start points into the arc, terminal end points out. */
    private static Direction endpointLongitudinal(ModelBlockEntity entity) {
        Direction towardArc = entity.getArcDirection() == null ? Direction.EAST : entity.getArcDirection();
        return entity.isTerminalEnd() ? towardArc.getOpposite() : towardArc;
    }

    private static Basis basis(Direction arcDirection, Vec3d preferredVertical, Vec3d preferredLateral) {
        if (arcDirection == null) return Basis.IDENTITY;
        Vec3d x = unit(arcDirection).normalize();
        Vec3d y = orthogonal(preferredVertical, x, new Vec3d(0,1,0));
        Vec3d z = orthogonal(preferredLateral, x, x.crossProduct(y));
        z = z.subtract(y.multiply(z.dotProduct(y)));
        if (z.lengthSquared() < EPS) z = x.crossProduct(y);
        z = z.normalize();
        if (z.dotProduct(preferredLateral) < 0.0) z = z.multiply(-1.0);
        return new Basis(x, y, z);
    }

    private static Vec3d orthogonal(Vec3d value, Vec3d tangent, Vec3d fallback) {
        Vec3d source = value == null || value.lengthSquared() < EPS ? fallback : value;
        Vec3d result = source.subtract(tangent.multiply(source.dotProduct(tangent)));
        if (result.lengthSquared() < EPS) {
            result = fallback.subtract(tangent.multiply(fallback.dotProduct(tangent)));
        }
        return result.lengthSquared() < EPS ? new Vec3d(0,1,0) : result.normalize();
    }

    private static Vec3d unit(Direction direction) {
        return new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private static Vector3f vec(Vec3d value) {
        return new Vector3f((float) value.x, (float) value.y, (float) value.z);
    }

    @Override
    public boolean isInRenderDistance(ModelBlockEntity entity, Vec3d cameraPos) {
        if (entity.getWorld() == null) return false;
        BlockState state = entity.getWorld().getBlockState(entity.getPos());
        if (state.getBlock() != com.slopeconnector.model.ModelSystemMod.MODEL_ENDPOINT_BLOCK) return false;
        Vec3d center = Vec3d.ofCenter(entity.getPos());
        double dx = center.x - cameraPos.x;
        double dy = center.y - cameraPos.y;
        double dz = center.z - cameraPos.z;
        double distance = getRenderDistance();
        return dx * dx + dy * dy + dz * dz <= distance * distance;
    }

    @Override
    public boolean rendersOutsideBoundingBox(ModelBlockEntity entity) {
        // Endpoint width/thickness may extend several blocks away from the central holder.
        return entity.getWorld() != null
                && entity.getWorld().getBlockState(entity.getPos()).getBlock()
                == com.slopeconnector.model.ModelSystemMod.MODEL_ENDPOINT_BLOCK;
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normalMatrix,
                             Vertex vertex, Vector3f normal, int light, int overlay,
                             int red, int green, int blue) {
        consumer.vertex(position, (float) vertex.pos.x, (float) vertex.pos.y, (float) vertex.pos.z)
                .color(red, green, blue, 255)
                .texture(vertex.u, vertex.v)
                .overlay(overlay)
                .light(light)
                .normal(normalMatrix, normal.x, normal.y, normal.z)
                .next();
    }

    private record Vertex(Vec3d pos, float u, float v) {}

    private record Basis(Vec3d x, Vec3d y, Vec3d z) {
        static final Basis IDENTITY = new Basis(new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1));

        Vec3d transform(Vec3d source) {
            Vec3d offset = source.subtract(new Vec3d(0.5, 0.5, 0.5));
            return new Vec3d(0.5, 0.5, 0.5)
                    .add(x.multiply(offset.x))
                    .add(y.multiply(offset.y))
                    .add(z.multiply(offset.z));
        }

        boolean reversesWinding() {
            return x.crossProduct(y).dotProduct(z) < 0.0;
        }
    }
}
