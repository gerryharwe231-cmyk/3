package com.slopeconnector.surface.mixin;

import com.slopeconnector.surface.orientation.ArcPlacementOrientationClientState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds exactly one new toggle below the existing 0.9.23 left-side controls. */
@Pseudo
@Mixin(targets = "com.slopeconnector.client.ArcWandConfigScreen", remap = false, priority = 2700)
public abstract class ArcPlacementOrientationScreenMixin extends Screen {
    private ButtonWidget slopeconnectorSurface$orientationButton;

    protected ArcPlacementOrientationScreenMixin() { super(Text.empty()); }

    @Inject(method = "method_25426", at = @At("TAIL"), remap = false)
    private void slopeconnectorSurface$addViewOrientation(CallbackInfo ci) {
        this.slopeconnectorSurface$orientationButton = this.addDrawableChild(
                ButtonWidget.builder(Text.literal(ArcPlacementOrientationClientState.label()), button -> {
                    ArcPlacementOrientationClientState.toggle();
                    button.setMessage(Text.literal(ArcPlacementOrientationClientState.label()));
                }).dimensions(18, 218, 130, 20).build());
    }

    @Inject(method = "method_25394", at = @At("HEAD"), remap = false)
    private void slopeconnectorSurface$extendLeftPanel(DrawContext context, int mouseX, int mouseY,
                                                        float delta, CallbackInfo ci) {
        context.fill(8, 172, 158, 248, 0xB0181818);
        context.drawTextWithShadow(this.textRenderer, Text.literal("放置朝向"), 18, 205, 0xD0D0D0);
        if (this.slopeconnectorSurface$orientationButton != null) {
            this.slopeconnectorSurface$orientationButton.setMessage(
                    Text.literal(ArcPlacementOrientationClientState.label()));
        }
    }
}
