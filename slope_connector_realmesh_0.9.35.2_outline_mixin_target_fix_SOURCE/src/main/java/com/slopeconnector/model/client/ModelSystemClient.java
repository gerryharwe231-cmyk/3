package com.slopeconnector.model.client;

import com.slopeconnector.model.ModelRenderWandItem;
import com.slopeconnector.model.ModelSystemMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class ModelSystemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRendererRegistry.register(ModelSystemMod.MODEL_BLOCK_ENTITY, ModelBlockRenderer::new);
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!isHoldingModelWand(client) || client.currentScreen != null) return;
            int x = 12, y = client.getWindow().getScaledHeight() - 52;
            context.drawTextWithShadow(client.textRenderer, Text.literal("模型渲染杖：右键获取/替换模型"), x, y, 0xFFFFFF);
            context.drawTextWithShadow(client.textRenderer, Text.literal("按 G 打开模型面板"), x, y + 12, 0xA0FFA0);
        });
    }

    private static boolean isHoldingModelWand(MinecraftClient client) {
        return client.player != null && (client.player.getMainHandStack().getItem() instanceof ModelRenderWandItem
                || client.player.getOffHandStack().getItem() instanceof ModelRenderWandItem);
    }
}
