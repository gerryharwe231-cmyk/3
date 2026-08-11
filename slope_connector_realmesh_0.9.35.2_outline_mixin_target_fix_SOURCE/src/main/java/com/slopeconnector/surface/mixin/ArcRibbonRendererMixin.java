package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.hotfix.client.UnifiedSurfaceArcRenderer;
import com.slopeconnector.hotfix.client.UvSafeArcRibbonRenderer;
import com.slopeconnector.model.ModelSystemMod;
import com.slopeconnector.model.ArcPrismTags;
import com.slopeconnector.model.client.ModelArcRenderer;
import com.slopeconnector.model.client.ModelTemplateArcRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves the exact 0.9.23 prism renderer while a component is still the pure-white Model Block.
 * Only after the model-render wand replaces sourceState with a captured model do we switch to the
 * BakedModel deformation renderer.  A failed captured-model render falls back to the 0.9.23 renderer.
 */
@Mixin(value = UvSafeArcRibbonRenderer.class, remap = false, priority = 3000)
public abstract class ArcRibbonRendererMixin {
    @Inject(method = "renderReplacement", at = @At("HEAD"), cancellable = true, remap = false)
    private static void slopeconnectorModel$selectRenderer(ArcRibbonBlockEntity entity, float tickDelta,
                                                            MatrixStack matrices,
                                                            VertexConsumerProvider consumers,
                                                            int light, int overlay,
                                                            CallbackInfo ci) {
        // Collision-only proxy holders intentionally have no visible mesh.  Skipping them here is
        // critical for widened/thick arcs: otherwise every proxy BE enters component discovery and
        // renderer caching every frame even though it can never emit a vertex.
        if (ArcPrismTags.isProxyOnly(entity)) {
            ci.cancel();
            return;
        }

        if (ModelSystemMod.isModelHolder(entity.getSourceState())) {
            // The pure-white template has no material continuity requirement.  Rendering raw prisms
            // directly avoids all topology/atlas failure modes while preserving original geometry.
            ModelTemplateArcRenderer.render(entity, matrices, consumers, light, overlay);
            ci.cancel();
            return;
        }

        if (ModelArcRenderer.renderReplacement(entity, tickDelta, matrices, consumers, light, overlay)) {
            ci.cancel();
            return;
        }

        UnifiedSurfaceArcRenderer.renderReplacement(entity, tickDelta, matrices, consumers, light, overlay);
        ci.cancel();
    }
}
