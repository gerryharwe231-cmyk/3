package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Records exactly which ArcRibbon block entities were written by one generator invocation. */
public final class ArcBuildGroupContext {
    private static final AtomicLong NEXT = new AtomicLong(0x5C10_0000_0000_0001L);
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

    private ArcBuildGroupContext() {}

    public static void begin(double widthSpan, double radialSpan, net.minecraft.util.math.Direction innerFace) {
        ACTIVE.set(new State(NEXT.getAndIncrement(), new LinkedHashSet<>(),
                Math.max(1.0, widthSpan), Math.max(1.0, radialSpan),
                innerFace == null ? net.minecraft.util.math.Direction.UP : innerFace));
    }

    public static boolean active() { return ACTIVE.get() != null; }

    public static void record(ArcRibbonBlockEntity entity) {
        State state = ACTIVE.get();
        if (state != null && entity != null) state.positions.add(entity.getPos().toImmutable());
    }

    /** Clears the recorder before tagging so the marker writes cannot recursively grow the set. */
    public static long finishAndTag(World world) {
        State state = ACTIVE.get();
        ACTIVE.remove();
        if (state == null || world == null || state.positions.isEmpty()) return 0L;
        BlockPos renderLeaderPos = chooseRenderLeader(state);
        double renderRadius = renderLeaderRadius(state, renderLeaderPos);
        int tagged = 0;
        for (BlockPos pos : state.positions) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof ArcRibbonBlockEntity ribbon)) continue;
            var prisms = new ArrayList<ArcRibbonBlockEntity.Prism>();
            boolean hasTopology = false;
            for (ArcRibbonBlockEntity.Prism prism : ribbon.getPrisms()) {
                if (ArcPrismTags.isGroupMarker(prism) || ArcPrismTags.isRenderLeader(prism)) continue;
                prisms.add(prism);
                if (!ArcPrismTags.isCollisionProxy(prism) && prism.xyz() != null && prism.xyz().length >= 24) {
                    hasTopology = true;
                }
            }
            // Do not tag surface-only cleanup holders or collision-only blocks: they must never become
            // logical arc members simply because they were touched during the same generator call.
            if (!hasTopology) continue;
            prisms.add(ArcPrismTags.groupMarker(state.groupId, state.widthSpan, state.radialSpan, state.innerFace));
            if (renderLeaderPos != null && pos.equals(renderLeaderPos)) {
                prisms.add(ArcPrismTags.renderLeader(renderRadius));
            }
            ribbon.setData(ribbon.getSourceState(), prisms, new ArrayList<>(ribbon.getSurfaces()));
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
            tagged++;
        }
        return tagged == 0 ? 0L : state.groupId;
    }

    private static BlockPos chooseRenderLeader(State state) {
        if (state == null || state.positions.isEmpty()) return null;
        Vec3d centroid = Vec3d.ZERO;
        for (BlockPos pos : state.positions) centroid = centroid.add(Vec3d.ofCenter(pos));
        centroid = centroid.multiply(1.0 / state.positions.size());
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : state.positions) {
            double distance = Vec3d.ofCenter(pos).squaredDistanceTo(centroid);
            if (distance < bestDistance) { bestDistance = distance; best = pos; }
        }
        return best;
    }

    private static double renderLeaderRadius(State state, BlockPos leader) {
        if (state == null || leader == null) return 64.0;
        Vec3d center = Vec3d.ofCenter(leader);
        double radius = 0.0;
        for (BlockPos pos : state.positions) radius = Math.max(radius, center.distanceTo(Vec3d.ofCenter(pos)));
        return radius + Math.max(state.widthSpan, state.radialSpan) * 0.5 + 32.0;
    }

    public static void clear() { ACTIVE.remove(); }

    private static final class State {
        final long groupId;
        final Set<BlockPos> positions;
        final double widthSpan;
        final double radialSpan;
        final net.minecraft.util.math.Direction innerFace;
        State(long groupId, Set<BlockPos> positions, double widthSpan, double radialSpan,
              net.minecraft.util.math.Direction innerFace) {
            this.groupId = groupId;
            this.positions = positions;
            this.widthSpan = widthSpan;
            this.radialSpan = radialSpan;
            this.innerFace = innerFace;
        }
    }
}
