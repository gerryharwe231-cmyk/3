package com.slopeconnector.surface.orientation;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client lookup used from chunk-model rendering. */
public final class PlacedOrientationClientCache {
    private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static boolean initialized;

    private PlacedOrientationClientCache() {}

    public record Entry(int quarterTurns, Identifier blockId) {}

    public static void initializeNetworking() {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(PlacedOrientationService.RESET_PACKET,
                (client, handler, buf, responseSender) -> client.execute(ENTRIES::clear));
        ClientPlayNetworking.registerGlobalReceiver(PlacedOrientationService.UPDATE_PACKET,
                (client, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int turns = buf.readByte();
                    Identifier blockId = buf.readIdentifier();
                    client.execute(() -> {
                        if (turns < 0 || Math.floorMod(turns, 4) == 0) ENTRIES.remove(pos.asLong());
                        else ENTRIES.put(pos.asLong(), new Entry(Math.floorMod(turns, 4), blockId));
                        if (client.world != null) {
                            BlockState state = client.world.getBlockState(pos);
                            client.world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS | Block.REDRAW_ON_MAIN_THREAD);
                        }
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(PlacedOrientationService.SETTING_PACKET,
                (client, handler, buf, responseSender) -> {
                    boolean value = buf.readBoolean();
                    client.execute(() -> ArcPlacementOrientationClientState.setFromServer(value));
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ENTRIES.clear();
            ArcPlacementOrientationClientState.setFromServer(false);
        });
    }

    public static int quarterTurns(BlockPos pos, BlockState state) {
        Entry entry = ENTRIES.get(pos.asLong());
        if (entry == null) return 0;
        Identifier current = Registries.BLOCK.getId(state.getBlock());
        return current.equals(entry.blockId()) ? entry.quarterTurns() : 0;
    }
}
