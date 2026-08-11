package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.model.ArcBuildGroupContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags all ArcRibbon BEs written by the same generator call with one persisted logical group id. */
@Mixin(value = ArcRibbonBlockEntity.class, remap = false, priority = 2650)
public abstract class ArcRibbonGroupTagMixin {
    @Inject(method = "setData", at = @At("RETURN"), remap = false)
    private void slopeconnectorSurface$recordGeneratedRibbon(CallbackInfo ci) {
        if (ArcBuildGroupContext.active()) {
            ArcBuildGroupContext.record((ArcRibbonBlockEntity) (Object) this);
        }
    }
}
