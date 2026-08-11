package com.slopeconnector.surface.mixin;

import com.slopeconnector.ArcSlopeWandItem;
import com.slopeconnector.model.ModelSystemMod;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The original ArcSlopeWandItem rejects arbitrary block entities before any of its normal logic runs.
 * Model Block is the single intentional exception.  Only this boolean check is bypassed; the original
 * item method continues unchanged after the redirect.
 */
@Mixin(value = ArcSlopeWandItem.class, remap = false)
public abstract class ArcWandModelBlockEntityBypassMixin {
    @Redirect(
            method = "method_7884",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/class_2680;method_31709()Z",
                    remap = false
            ),
            remap = false
    )
    private boolean slopeconnectorSurface$allowOnlyModelBlockEntity(BlockState state) {
        return ModelSystemMod.isModelHolder(state) ? false : state.hasBlockEntity();
    }
}
