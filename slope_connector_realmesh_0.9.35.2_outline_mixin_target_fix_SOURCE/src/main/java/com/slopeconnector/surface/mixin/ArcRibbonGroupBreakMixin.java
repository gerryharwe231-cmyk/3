package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcComponentFinder;
import com.slopeconnector.model.ArcPrismTags;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the single-render-leader cache coherent when an individual visible arc holder is broken.
 * Collision-only helpers are untargetable and therefore do not enter this path.
 */
@Mixin(value = AbstractBlock.class, priority = 3030)
public abstract class ArcRibbonGroupBreakMixin {
    @Inject(method = "onStateReplaced", at = @At("HEAD"), require = 0)
    private void slopeconnectorSurface$refreshGroupAfterBreak(BlockState state, World world, BlockPos pos,
                                                               BlockState newState, boolean moved,
                                                               CallbackInfo ci) {
        if (state.getBlock() != ArcHotfixMod.ARC_RIBBON || state.getBlock() == newState.getBlock()) return;
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof ArcRibbonBlockEntity broken) || ArcPrismTags.isProxyOnly(broken)) return;
        long groupId = ArcPrismTags.groupId(broken);
        if (groupId == 0L) return;

        ArcComponentFinder.Component component;
        try { component = ArcComponentFinder.build(broken); }
        catch (RuntimeException ignored) { return; }
        if (component == null) return;
        List<ArcRibbonBlockEntity> survivors = new ArrayList<>();
        for (ArcRibbonBlockEntity member : component.members()) {
            if (!member.getPos().equals(pos) && ArcPrismTags.groupId(member) == groupId
                    && !ArcPrismTags.isProxyOnly(member)) survivors.add(member);
        }
        if (survivors.isEmpty()) return;

        Vec3d centroid = Vec3d.ZERO;
        for (ArcRibbonBlockEntity member : survivors) centroid = centroid.add(Vec3d.ofCenter(member.getPos()));
        centroid = centroid.multiply(1.0 / survivors.size());
        ArcRibbonBlockEntity leader = survivors.get(0);
        double best = Vec3d.ofCenter(leader.getPos()).squaredDistanceTo(centroid);
        for (ArcRibbonBlockEntity candidate : survivors) {
            double distance = Vec3d.ofCenter(candidate.getPos()).squaredDistanceTo(centroid);
            if (distance < best) { best = distance; leader = candidate; }
        }
        Vec3d leaderCenter = Vec3d.ofCenter(leader.getPos());
        double radius = 32.0;
        for (ArcRibbonBlockEntity member : survivors) {
            radius = Math.max(radius, leaderCenter.distanceTo(Vec3d.ofCenter(member.getPos())) + 32.0);
        }

        for (ArcRibbonBlockEntity member : survivors) {
            List<ArcRibbonBlockEntity.Prism> prisms = new ArrayList<>();
            for (ArcRibbonBlockEntity.Prism prism : member.getPrisms()) {
                if (!ArcPrismTags.isRenderLeader(prism)) prisms.add(prism);
            }
            if (member == leader) prisms.add(ArcPrismTags.renderLeader(radius));
            member.setData(member.getSourceState(), prisms, new ArrayList<>(member.getSurfaces()));
            BlockState holder = world.getBlockState(member.getPos());
            world.updateListeners(member.getPos(), holder, holder, 2);
        }
    }
}
