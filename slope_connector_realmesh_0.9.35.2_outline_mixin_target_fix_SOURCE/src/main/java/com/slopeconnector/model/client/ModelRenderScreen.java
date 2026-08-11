package com.slopeconnector.model.client;

import com.slopeconnector.model.ModelRenderWandItem;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Compact inventory-style captured-model screen. */
public final class ModelRenderScreen extends Screen {
    private static final int SLOT = 44;

    public ModelRenderScreen() { super(Text.literal("模型渲染杖")); }

    @Override
    protected void init() {
        int buttonWidth = 118;
        int gap = 8;
        int left = width / 2 - buttonWidth - gap / 2;
        int y = height / 2 + 70;
        addDrawableChild(ButtonWidget.builder(Text.literal("清空当前模型"), button -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendChatCommand("slopeconnector modelclear");
            }
        }).dimensions(left, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> close())
                .dimensions(width / 2 + gap / 2, y, buttonWidth, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        int cx = width / 2;
        int top = height / 2 - 96;
        context.drawCenteredTextWithShadow(textRenderer, title, cx, top, 0xFFFFFF);

        BlockState state = captured();
        int slotX = cx - SLOT / 2;
        int slotY = top + 24;
        drawSlot(context, slotX, slotY);

        if (state == null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("未获取模型"),
                    cx, slotY + SLOT + 10, 0xFFD080);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("右键一个方块即可获取"),
                    cx, slotY + SLOT + 27, 0xBFBFBF);
        } else {
            ItemStack preview = new ItemStack(state.getBlock().asItem());
            if (!preview.isEmpty()) {
                context.getMatrices().push();
                context.getMatrices().translate(slotX + 6, slotY + 6, 100.0f);
                context.getMatrices().scale(2.0f, 2.0f, 1.0f);
                context.drawItem(preview, 0, 0);
                context.getMatrices().pop();
            } else {
                context.drawCenteredTextWithShadow(textRenderer, Text.literal("无物品图标"),
                        cx, slotY + 18, 0xAAAAAA);
            }
            context.drawCenteredTextWithShadow(textRenderer, state.getBlock().getName(),
                    cx, slotY + SLOT + 9, 0xFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(Registries.BLOCK.getId(state.getBlock()).toString()),
                    cx, slotY + SLOT + 25, 0xA8A8A8);
            String summary = stateSummary(state);
            if (!summary.isEmpty()) {
                context.drawCenteredTextWithShadow(textRenderer, Text.literal(summary),
                        cx, slotY + SLOT + 42, 0xBFBFBF);
            }
        }

        context.drawCenteredTextWithShadow(textRenderer, Text.literal("G：打开 / 关闭模型面板"),
                cx, height / 2 + 50, 0xA0FFA0);
        super.render(context, mouseX, mouseY, delta);
    }

    private static void drawSlot(DrawContext context, int x, int y) {
        context.fill(x - 2, y - 2, x + SLOT + 2, y + SLOT + 2, 0xFF111111);
        context.fill(x, y, x + SLOT, y + SLOT, 0xFF8B8B8B);
        context.fill(x + 2, y + 2, x + SLOT - 2, y + SLOT - 2, 0xFF373737);
    }

    @Override public boolean shouldPause() { return false; }

    private static ItemStack heldWand() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;
        if (client.player.getMainHandStack().getItem() instanceof ModelRenderWandItem) {
            return client.player.getMainHandStack();
        }
        if (client.player.getOffHandStack().getItem() instanceof ModelRenderWandItem) {
            return client.player.getOffHandStack();
        }
        return null;
    }

    private static BlockState captured() {
        ItemStack stack = heldWand();
        return stack == null ? null : ModelRenderWandItem.getCaptured(stack);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String stateSummary(BlockState state) {
        if (state.getProperties().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Property property : state.getProperties()) {
            Comparable value = state.get(property);
            parts.add(property.getName() + "=" + property.name(value));
        }
        String text = "状态：" + String.join(", ", parts);
        return text.length() <= 66 ? text : text.substring(0, 63) + "...";
    }
}
