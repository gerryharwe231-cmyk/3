package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcPrismTags;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Collision helpers are physical only; the player must never ray-target/break an invisible proxy. */
@Mixin(value = AbstractBlock.class, priority = 3020)
public abstract class ArcRibbonProxyOutlineMixin {
    @Inject(method = "getOutlineShape", at = @At("HEAD"), cancellable = true, require = 0)
    private void slopeconnectorSurface$hideProxyOutline(BlockState state, BlockView world, BlockPos pos,
                                                         ShapeContext context,
                                                         CallbackInfoReturnable<VoxelShape> cir) {
        // getOutlineShape is declared by Minecraft AbstractBlock, not by the embedded ArcRibbonBlock.
        // Keep this global hook effectively no-op for every other block type.
        if (state == null || state.getBlock() != ArcHotfixMod.ARC_RIBBON) return;
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof ArcRibbonBlockEntity ribbon && ArcPrismTags.isProxyOnly(ribbon)) {
            cir.setReturnValue(VoxelShapes.empty());
        }
    }
}
