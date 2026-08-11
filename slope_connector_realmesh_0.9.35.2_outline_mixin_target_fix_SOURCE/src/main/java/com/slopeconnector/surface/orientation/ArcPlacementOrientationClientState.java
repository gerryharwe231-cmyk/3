package com.slopeconnector.surface.orientation;

import net.minecraft.client.MinecraftClient;

/** Client mirror of the server toggle used by the existing arc-wand G panel. */
public final class ArcPlacementOrientationClientState {
    private static volatile boolean enabled;

    private ArcPlacementOrientationClientState() {}

    public static boolean enabled() { return enabled; }
    public static void setFromServer(boolean value) { enabled = value; }
    public static String label() { return "视角定向放置：" + (enabled ? "开" : "关"); }

    public static void toggle() {
        enabled = !enabled;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand("slopeconnector vieworient " + (enabled ? "on" : "off"));
        }
    }
}
