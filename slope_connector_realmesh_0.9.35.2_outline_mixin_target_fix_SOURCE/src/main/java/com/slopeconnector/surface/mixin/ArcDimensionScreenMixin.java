package com.slopeconnector.surface.mixin;

import com.slopeconnector.surface.dimensions.ArcDimensionClientState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renames the two existing geometric controls to match their actual effect. */
@Pseudo
@Mixin(targets = "com.slopeconnector.client.ArcWandConfigScreen", remap = false, priority = 2600)
public abstract class ArcDimensionScreenMixin extends Screen {
    private ButtonWidget slopeconnectorSurface$upDownValue;

    protected ArcDimensionScreenMixin() {
        super(Text.empty());
    }

    @Inject(method = "method_25426", at = @At("TAIL"), remap = false)
    private void slopeconnectorSurface$addDimensionRow(CallbackInfo ci) {
        // Match the embedded "上下厚度" control: +/- on one row, full-width value below it.
        int rightX = this.width - 148;
        int y = 166;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("侧面 -"), button -> {
            ArcDimensionClientState.changeUpDown(-1);
            slopeconnectorSurface$refreshDimensionLabel();
        }).dimensions(rightX, y, 64, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("侧面 +"), button -> {
            ArcDimensionClientState.changeUpDown(1);
            slopeconnectorSurface$refreshDimensionLabel();
        }).dimensions(rightX + 66, y, 64, 20).build());

        this.slopeconnectorSurface$upDownValue = this.addDrawableChild(ButtonWidget.builder(
                Text.literal(ArcDimensionClientState.label()), button -> {})
                .dimensions(rightX, y + 24, 130, 20).build());
        this.slopeconnectorSurface$upDownValue.active = false;
    }

    @Inject(method = "method_25394", at = @At("HEAD"), remap = false)
    private void slopeconnectorSurface$extendRightPanel(DrawContext context, int mouseX, int mouseY,
                                                         float delta, CallbackInfo ci) {
        context.fill(this.width - 158, 160, this.width - 8, 216, 0xB0181818);
    }

    @ModifyConstant(method = "method_25426", constant = @Constant(stringValue = "宽度 -"),
            remap = false, require = 0)
    private String slopeconnectorSurface$leftRightMinus(String original) {
        return "上下 -";
    }

    @ModifyConstant(method = "method_25426", constant = @Constant(stringValue = "宽度 +"),
            remap = false, require = 0)
    private String slopeconnectorSurface$leftRightPlus(String original) {
        return "上下 +";
    }

    @ModifyConstant(method = "refreshLabels",
            constant = @Constant(stringValue = "当前宽度：\u0001"), remap = false, require = 0)
    private String slopeconnectorSurface$leftRightLabel(String original) {
        return "上下厚度：\u0001";
    }

    @ModifyConstant(method = "method_25394",
            constant = @Constant(stringValue = "右侧：宽度与操作"), remap = false, require = 0)
    private String slopeconnectorSurface$dimensionHeader(String original) {
        return "右侧：上下厚度与侧面宽度";
    }

    private void slopeconnectorSurface$refreshDimensionLabel() {
        if (this.slopeconnectorSurface$upDownValue != null) {
            this.slopeconnectorSurface$upDownValue.setMessage(Text.literal(ArcDimensionClientState.label()));
        }
    }
}
