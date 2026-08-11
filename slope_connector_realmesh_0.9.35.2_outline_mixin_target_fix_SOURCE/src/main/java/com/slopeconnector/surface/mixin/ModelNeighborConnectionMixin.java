package com.slopeconnector.surface.mixin;

import com.slopeconnector.model.ModelBlockEntity;
import com.slopeconnector.model.ModelSystemMod;
import com.slopeconnector.surface.ConnectionStateHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a real neighboring fence/pane/wall see the ModelBlock endpoint as the captured real block.
 * This deliberately invokes the block's native neighbour-update code instead of guessing its state
 * properties, which covers Conquest Reforged's custom Pane/FenceLayered/WallNew/WallOld logic.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class ModelNeighborConnectionMixin {
    @Inject(method="getStateForNeighborUpdate",at=@At("RETURN"),cancellable=true)
    private void slopeconnectorModel$neighbor(Direction direction, BlockState neighborState, WorldAccess world,
                                               BlockPos pos, BlockPos neighborPos,
                                               CallbackInfoReturnable<BlockState> cir){
        if(direction.getAxis().isVertical()||neighborState.getBlock()!=ModelSystemMod.MODEL_ENDPOINT_BLOCK)return;
        BlockEntity be=world.getBlockEntity(neighborPos);
        if(!(be instanceof ModelBlockEntity model)||!model.isSkinned())return;
        BlockState original=(BlockState)(Object)this;
        if(!ConnectionStateHelper.sameFamily(original,model.getCapturedState()))return;
        BlockState represented=model.getDisplayState();
        BlockState nativeResult=ConnectionStateHelper.nativeUpdateWithRepresentedNeighbor(
                original,direction,world,pos,neighborPos,represented);
        if(nativeResult.equals(original)) {
            nativeResult=ConnectionStateHelper.forceConnection(original,direction);
        }
        if (ConnectionStateHelper.orientationOnlyConnectedProfile(original)) {
            // Axis/facing-only Conquest profiles have no N/E/S/W arm to toggle.  Align the actual
            // source module toward the skinned endpoint so its baked geometry reaches the same join
            // plane as an ordinary neighbour of the same family.
            nativeResult=ConnectionStateHelper.alignAxisOrFacing(nativeResult,direction);
        }
        cir.setReturnValue(nativeResult);
    }
}
