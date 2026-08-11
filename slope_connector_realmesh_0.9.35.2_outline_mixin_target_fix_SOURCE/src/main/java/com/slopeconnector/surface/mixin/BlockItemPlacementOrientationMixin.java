package com.slopeconnector.surface.mixin;

import com.slopeconnector.surface.orientation.ArcPlacementOrientationSettings;
import com.slopeconnector.surface.orientation.PlacedOrientationService;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the player's horizontal view as the placed block's new north direction when enabled. */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacementOrientationMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void slopeconnectorSurface$viewOrientation(ItemPlacementContext context,
                                                        CallbackInfoReturnable<ActionResult> cir) {
        if (!cir.getReturnValue().isAccepted()) return;
        if (!(context.getWorld() instanceof ServerWorld world)) return;
        if (!(context.getPlayer() instanceof ServerPlayerEntity player)) return;
        if (!ArcPlacementOrientationSettings.enabled(player)) return;
        Direction desired = player.getHorizontalFacing();
        PlacedOrientationService.applyPlacement(world, player, context.getBlockPos(), desired);
    }
}
