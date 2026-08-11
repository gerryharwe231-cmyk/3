package com.slopeconnector.surface.client;

import com.slopeconnector.ArcSlopeWandItem;
import com.slopeconnector.client.ArcWandConfigScreen;
import com.slopeconnector.model.ModelRenderWandItem;
import com.slopeconnector.model.client.ModelRenderScreen;
import com.slopeconnector.surface.client.orientation.ViewOrientationModelPlugin;
import com.slopeconnector.surface.orientation.PlacedOrientationClientCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** One real Fabric key binding for both connector panels. */
public final class SurfaceRefineClient implements ClientModInitializer {
    public static KeyBinding OPEN_PANEL_KEY;

    @Override
    public void onInitializeClient() {
        ViewOrientationModelPlugin.register();
        PlacedOrientationClientCache.initializeNetworking();
        OPEN_PANEL_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.slopeconnector_surface_refine.open_panel",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_G,
                "category.slopeconnector_surface_refine"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_PANEL_KEY.wasPressed()) {
                if (client.currentScreen != null || client.player == null) continue;
                PanelTarget target = target(client);
                if (target == PanelTarget.MODEL) {
                    client.setScreen(new ModelRenderScreen());
                } else if (target == PanelTarget.ARC) {
                    // This is the original ArcWandConfigScreen class from the unchanged embedded core.
                    client.setScreen(new ArcWandConfigScreen());
                }
            }
        });
    }

    private static PanelTarget target(MinecraftClient client) {
        ItemStack main = client.player.getMainHandStack();
        ItemStack off = client.player.getOffHandStack();
        PanelTarget mainTarget = target(main.getItem());
        if (mainTarget != PanelTarget.NONE) return mainTarget;
        return target(off.getItem());
    }

    private static PanelTarget target(Item item) {
        if (item instanceof ModelRenderWandItem) return PanelTarget.MODEL;
        if (item instanceof ArcSlopeWandItem) return PanelTarget.ARC;
        return PanelTarget.NONE;
    }

    private enum PanelTarget { NONE, ARC, MODEL }
}
