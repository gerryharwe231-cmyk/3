package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.hotfix.client.UvSafeArcRibbonRenderer;
import com.slopeconnector.model.ArcPrismTags;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;

/** Early dispatcher filtering for collision proxies and captured-model follower BEs. */
@Mixin(value = UvSafeArcRibbonRenderer.class, remap = false, priority = 3010)
public abstract class ArcRibbonRenderDistanceMixin {
    public boolean isInRenderDistance(ArcRibbonBlockEntity entity, Vec3d cameraPos) {
        if (ArcPrismTags.isProxyOnly(entity)) return false;

        // Every logical arc group (including the pure-white template stage) owns one central render
        // leader.  Followers never enter BER dispatch.  This changes dense white-arc cost from
        // O(number of ArcRibbon block entities) BER calls to O(number of logical arcs).
        if (ArcPrismTags.groupId(entity) != 0L) {
            if (!ArcPrismTags.isRenderLeader(entity)) return false;
            double radius = ArcPrismTags.renderLeaderRadius(entity);
            Vec3d center = Vec3d.ofCenter(entity.getPos());
            return center.squaredDistanceTo(cameraPos) <= radius * radius;
        }

        Vec3d center=Vec3d.ofCenter(entity.getPos());
        return center.squaredDistanceTo(cameraPos) <= 48.0 * 48.0;
    }
}
