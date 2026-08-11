package com.slopeconnector.model.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcComponentFinder;
import com.slopeconnector.model.ArcStationFrames;
import com.slopeconnector.model.ModelSystemMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pure-white template renderer built from shared station cross-sections.
 *
 * <p>0.9.26 extended every prism at both ends to hide cracks.  Longitudinal side faces then overlap
 * on the bottom/top and z-fight, while the faceMask can still omit an externally-visible face.  This
 * renderer does neither: every segment gets four longitudinal faces, no internal end cap, and the
 * two neighboring segments literally reuse the same four station corner positions.</p>
 */
public final class ModelTemplateArcRenderer {
    private static final double OWNER_RENDER_RADIUS = 48.0;
    private static final double OWNER_RENDER_RADIUS_SQ = OWNER_RENDER_RADIUS * OWNER_RENDER_RADIUS;
    private static final Map<ArcRibbonBlockEntity, CachedTemplate> CACHE = new WeakHashMap<>();

    private ModelTemplateArcRenderer() {}

    public static void render(ArcRibbonBlockEntity entity, MatrixStack matrices,
                              VertexConsumerProvider consumers, int fallbackLight, int overlay) {
        if (entity.getWorld() == null) return;
        TemplateHandle handle;
        try {
            handle = handle(entity);
        } catch (RuntimeException error) {
            renderFallback(entity, matrices, consumers, fallbackLight, overlay);
            return;
        }
        if (handle == null) {
            renderFallback(entity, matrices, consumers, fallbackLight, overlay);
            return;
        }
        boolean consolidated = handle.renderLeader() != null;
        if (consolidated && !handle.renderLeader().equals(entity.getPos())) return;

        Sprite sprite = MinecraftClient.getInstance().getBlockRenderManager()
                .getModel(ModelSystemMod.MODEL_BLOCK.getDefaultState()).getParticleSprite();
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(ModelSystemMod.MODEL_BLOCK.getDefaultState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos origin = entity.getPos();
        Vec3d viewer = MinecraftClient.getInstance().player == null
                ? Vec3d.ofCenter(entity.getPos()) : MinecraftClient.getInstance().player.getPos();
        Iterable<Map.Entry<BlockPos,List<Strip>>> entries = consolidated
                ? nearbyEntries(handle.byOwner(), handle.ownersByChunk(), viewer)
                : List.of(Map.entry(entity.getPos(), handle.byOwner().getOrDefault(entity.getPos(), List.of())));
        Map<BlockPos,int[]> lightCache = new HashMap<>();
        for (Map.Entry<BlockPos,List<Strip>> ownerEntry : entries) {
            if (ownerEntry.getValue().isEmpty()) continue;
            if (Vec3d.ofCenter(ownerEntry.getKey()).squaredDistanceTo(viewer) > OWNER_RENDER_RADIUS_SQ) continue;
            int[] directionalLights = lightCache.computeIfAbsent(ownerEntry.getKey(), ignored -> new int[]{-1,-1,-1,-1,-1,-1});
            for (Strip strip : ownerEntry.getValue()) {
                for (int edge = 0; edge < 4; edge++) {
                    int next = (edge + 1) & 3;
                    quad(entity, consumer, position, normalMatrix, origin, sprite,
                            strip.start()[edge], strip.end()[edge], strip.end()[next], strip.start()[next],
                            strip.center(), ownerEntry.getKey(), directionalLights, fallbackLight, overlay);
                }
            }
        }
    }

    private static Iterable<Map.Entry<BlockPos,List<Strip>>> nearbyEntries(
            Map<BlockPos,List<Strip>> byOwner, Map<Long,List<BlockPos>> ownersByChunk, Vec3d viewer) {
        int centerX=((int)Math.floor(viewer.x))>>4;
        int centerZ=((int)Math.floor(viewer.z))>>4;
        int radius=(int)Math.ceil(OWNER_RENDER_RADIUS/16.0)+1;
        List<Map.Entry<BlockPos,List<Strip>>> result=new ArrayList<>();
        for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
            List<BlockPos> owners=ownersByChunk.get(chunkKey(centerX+dx,centerZ+dz));
            if(owners==null) continue;
            for(BlockPos owner:owners) {
                List<Strip> strips=byOwner.get(owner);
                if(strips!=null&&!strips.isEmpty()) result.add(Map.entry(owner,strips));
            }
        }
        return result;
    }

    private static Map<Long,List<BlockPos>> chunkIndex(Map<BlockPos,? extends List<?>> byOwner) {
        Map<Long,List<BlockPos>> index=new HashMap<>();
        for(BlockPos pos:byOwner.keySet()) index.computeIfAbsent(chunkKey(pos.getX()>>4,pos.getZ()>>4),ignored->new ArrayList<>()).add(pos);
        Map<Long,List<BlockPos>> frozen=new HashMap<>();
        index.forEach((key,value)->frozen.put(key,List.copyOf(value)));
        return Map.copyOf(frozen);
    }

    private static long chunkKey(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}

    private static TemplateHandle handle(ArcRibbonBlockEntity entity) {
        CachedTemplate cached = CACHE.get(entity);
        if (cached != null && cached.handle().matches(entity)) return cached.handle();
        ArcComponentFinder.Component component = ArcComponentFinder.build(entity);
        if (component == null || component.segments().isEmpty()) return null;
        List<ArcStationFrames.Station> stations = new ArrayList<>(ArcStationFrames.build(component));
        if (stations.size() != component.segments().size() + 1) return null;
        if (component.startModelBlock() != null
                && component.world().getBlockEntity(component.startModelBlock()) instanceof com.slopeconnector.model.ModelBlockEntity model) {
            stations.set(0, ArcStationFrames.alignEndpoint(stations.get(0), model));
        }
        if (component.endModelBlock() != null
                && component.world().getBlockEntity(component.endModelBlock()) instanceof com.slopeconnector.model.ModelBlockEntity model) {
            int last = stations.size() - 1;
            stations.set(last, ArcStationFrames.alignEndpoint(stations.get(last), model));
        }
        Map<BlockPos, List<Strip>> byOwner = new HashMap<>();
        for (int i = 0; i < component.segments().size(); i++) {
            ArcComponentFinder.Segment segment = component.segments().get(i);
            Vec3d[] start = ArcStationFrames.section(stations.get(i));
            Vec3d[] end = ArcStationFrames.section(stations.get(i + 1));
            Vec3d center = stations.get(i).center().add(stations.get(i + 1).center()).multiply(0.5);
            byOwner.computeIfAbsent(segment.owner().getPos(), ignored -> new ArrayList<>())
                    .add(new Strip(start, end, center));
        }
        Map<BlockPos, Integer> revisions = new HashMap<>();
        Map<BlockPos, net.minecraft.block.BlockState> states = new HashMap<>();
        BlockPos renderLeader = null;
        for (ArcRibbonBlockEntity member : component.members()) {
            revisions.put(member.getPos(), member.getRenderRevision());
            states.put(member.getPos(), member.getSourceState());
            if (com.slopeconnector.model.ArcPrismTags.isRenderLeader(member)) renderLeader = member.getPos();
        }
        Map<BlockPos, List<Strip>> frozen = new HashMap<>();
        byOwner.forEach((pos, list) -> frozen.put(pos, List.copyOf(list)));
        Map<BlockPos,List<Strip>> frozenOwners=Map.copyOf(frozen);
        TemplateHandle built = new TemplateHandle(frozenOwners, chunkIndex(frozenOwners), Map.copyOf(revisions), Map.copyOf(states), renderLeader);
        for (ArcRibbonBlockEntity member : component.members()) CACHE.put(member, new CachedTemplate(built));
        return built;
    }

    /**
     * Component reconstruction can fail while chunks are still streaming.  The fallback deliberately
     * renders all four longitudinal sides of each raw prism, no expansion and no end caps.  It is
     * therefore still a full straight block-like strip and cannot reintroduce the old z-fight.
     */
    private static void renderFallback(ArcRibbonBlockEntity entity, MatrixStack matrices,
                                       VertexConsumerProvider consumers, int fallbackLight, int overlay) {
        Sprite sprite = MinecraftClient.getInstance().getBlockRenderManager()
                .getModel(ModelSystemMod.MODEL_BLOCK.getDefaultState()).getParticleSprite();
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(ModelSystemMod.MODEL_BLOCK.getDefaultState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos origin = entity.getPos();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (com.slopeconnector.model.ArcPrismTags.isMetadata(prism)) continue;
            float[] v = prism.xyz();
            if (v == null || v.length != 24) continue;
            Vec3d[] p = new Vec3d[8];
            for (int i = 0; i < 8; i++) {
                p[i] = new Vec3d(origin.getX()+v[i*3], origin.getY()+v[i*3+1], origin.getZ()+v[i*3+2]);
            }
            Vec3d center = average(p);
            int[] lights = new int[]{-1,-1,-1,-1,-1,-1};
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[0],p[4],p[5],p[1],center,origin,lights,fallbackLight,overlay);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[1],p[5],p[6],p[2],center,origin,lights,fallbackLight,overlay);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[2],p[6],p[7],p[3],center,origin,lights,fallbackLight,overlay);
            quad(entity,consumer,position,normalMatrix,origin,sprite,p[3],p[7],p[4],p[0],center,origin,lights,fallbackLight,overlay);
        }
    }

    private static void quad(ArcRibbonBlockEntity entity, VertexConsumer consumer,
                             Matrix4f position, Matrix3f normalMatrix, BlockPos origin, Sprite sprite,
                             Vec3d a, Vec3d b, Vec3d c, Vec3d d, Vec3d segmentCenter,
                             BlockPos ownerPos, int[] directionalLights,
                             int fallbackLight, int overlay) {
        Vec3d[] points = {a,b,c,d};
        Vector3f normal = normal(points);
        if (normal == null) return;
        Vec3d faceCenter = a.add(b).add(c).add(d).multiply(0.25);
        Vec3d outward = faceCenter.subtract(segmentCenter);
        if (normal.x*outward.x + normal.y*outward.y + normal.z*outward.z < 0.0) {
            Vec3d swap = points[1]; points[1] = points[3]; points[3] = swap;
            normal.mul(-1f);
        }
        net.minecraft.util.math.Direction direction = dominant(normal);
        int lightIndex = direction.ordinal();
        int light = directionalLights[lightIndex];
        if (light < 0) {
            light = ModelRenderLighting.sample(entity.getWorld(), ModelSystemMod.MODEL_BLOCK.getDefaultState(),
                    Vec3d.ofCenter(ownerPos), normal, fallbackLight);
            directionalLights[lightIndex] = light;
        }
        emit(consumer,position,normalMatrix,origin,points[0],sprite.getMinU(),sprite.getMinV(),normal,light,overlay);
        emit(consumer,position,normalMatrix,origin,points[1],sprite.getMaxU(),sprite.getMinV(),normal,light,overlay);
        emit(consumer,position,normalMatrix,origin,points[2],sprite.getMaxU(),sprite.getMaxV(),normal,light,overlay);
        emit(consumer,position,normalMatrix,origin,points[3],sprite.getMinU(),sprite.getMaxV(),normal,light,overlay);
    }

    private static Vector3f normal(Vec3d[] p) {
        Vector3f a = vec(p[0]), b = vec(p[1]), c = vec(p[2]);
        Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        return n.lengthSquared() < 1.0E-10f ? null : n.normalize();
    }

    private static net.minecraft.util.math.Direction dominant(Vector3f normal) {
        float ax=Math.abs(normal.x), ay=Math.abs(normal.y), az=Math.abs(normal.z);
        if (ay>=ax && ay>=az) return normal.y>=0 ? net.minecraft.util.math.Direction.UP : net.minecraft.util.math.Direction.DOWN;
        if (ax>=az) return normal.x>=0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST;
        return normal.z>=0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH;
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normalMatrix,
                             BlockPos origin, Vec3d point, float u, float v, Vector3f normal,
                             int light, int overlay) {
        consumer.vertex(position,(float)(point.x-origin.getX()),(float)(point.y-origin.getY()),(float)(point.z-origin.getZ()))
                .color(255,255,255,255).texture(u,v).overlay(overlay).light(light)
                .normal(normalMatrix,normal.x,normal.y,normal.z).next();
    }

    private static Vec3d average(Vec3d[] values) {
        Vec3d sum = Vec3d.ZERO; for (Vec3d value : values) sum = sum.add(value); return sum.multiply(1.0/values.length);
    }
    private static Vector3f vec(Vec3d value){return new Vector3f((float)value.x,(float)value.y,(float)value.z);}

    private record Strip(Vec3d[] start, Vec3d[] end, Vec3d center) {}
    private record TemplateHandle(Map<BlockPos, List<Strip>> byOwner,
                                  Map<Long,List<BlockPos>> ownersByChunk,
                                  Map<BlockPos,Integer> memberRevisions,
                                  Map<BlockPos,net.minecraft.block.BlockState> memberStates,
                                  BlockPos renderLeader) {
        boolean matches(ArcRibbonBlockEntity entity) {
            if (entity.getWorld()==null) return false;
            Integer expectedRevision=memberRevisions.get(entity.getPos());
            if (expectedRevision==null || entity.getRenderRevision()!=expectedRevision) return false;
            net.minecraft.block.BlockState expected=memberStates.get(entity.getPos());
            return expected==null || expected.equals(entity.getSourceState());
        }
    }
    private record CachedTemplate(TemplateHandle handle) {}
}
