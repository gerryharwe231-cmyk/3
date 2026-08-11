package com.slopeconnector.surface.orientation;

import com.slopeconnector.model.ModelSystemMod;
import com.slopeconnector.surface.ConnectionStateHelper;
import com.slopeconnector.surface.SurfaceRefineMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Optional;

/** Server-side placement orientation and client synchronization. */
public final class PlacedOrientationService {
    public static final Identifier RESET_PACKET = new Identifier(SurfaceRefineMod.MOD_ID, "orientation_reset");
    public static final Identifier UPDATE_PACKET = new Identifier(SurfaceRefineMod.MOD_ID, "orientation_update");
    public static final Identifier SETTING_PACKET = new Identifier(SurfaceRefineMod.MOD_ID, "orientation_setting");
    private static final String STATE_ID = SurfaceRefineMod.MOD_ID + "_placed_orientation";

    private PlacedOrientationService() {}

    public static void initialize() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerWorld serverWorld) remove(serverWorld, pos);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            sendAll(player);
            sendSetting(player, ArcPlacementOrientationSettings.enabled(player));
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            sendAll(player);
            sendSetting(player, ArcPlacementOrientationSettings.enabled(player));
        });
    }

    public static void applyPlacement(ServerWorld world, ServerPlayerEntity player,
                                      BlockPos pos, Direction desiredNorth) {
        if (world == null || player == null || desiredNorth == null
                || desiredNorth.getAxis().isVertical()) return;
        BlockState original = world.getBlockState(pos);
        if (original.isAir() || ModelSystemMod.isModelHolder(original)) {
            remove(world, pos);
            return;
        }

        BlockState oriented = applyExplicitState(original, desiredNorth);
        if (!oriented.equals(original)) {
            world.setBlockState(pos, oriented, 3);
            remove(world, pos);
            return;
        }

        // Connection arms are semantic geometry, not merely a texture orientation. Their native
        // N/E/S/W neighbor logic must remain authoritative. Facing/axis families were handled above.
        if (ConnectionStateHelper.isSupported(original)) {
            remove(world, pos);
            return;
        }
        if (original.hasBlockEntity() || original.getRenderType() != BlockRenderType.MODEL) {
            remove(world, pos);
            return;
        }

        int turns = quarterTurnsFromNorth(desiredNorth);
        Identifier id = Registries.BLOCK.getId(original.getBlock());
        state(world).put(pos, turns, id);
        sendUpdate(world, pos, turns, id);
    }

    public static void remove(ServerWorld world, BlockPos pos) {
        state(world).remove(pos);
        sendUpdate(world, pos, -1, Registries.BLOCK.getId(world.getBlockState(pos).getBlock()));
    }

    public static void sendSetting(ServerPlayerEntity player, boolean enabled) {
        if (player == null) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(enabled);
        ServerPlayNetworking.send(player, SETTING_PACKET, buf);
    }

    public static void sendAll(ServerPlayerEntity player) {
        if (player == null || !(player.getWorld() instanceof ServerWorld world)) return;
        ServerPlayNetworking.send(player, RESET_PACKET, PacketByteBufs.empty());
        for (var entry : state(world).entries().entrySet()) {
            BlockPos pos = BlockPos.fromLong(entry.getKey());
            PlacedOrientationState.Entry value = entry.getValue();
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(pos);
            buf.writeByte(value.quarterTurns());
            buf.writeIdentifier(value.blockId());
            ServerPlayNetworking.send(player, UPDATE_PACKET, buf);
        }
    }

    private static void sendUpdate(ServerWorld world, BlockPos pos, int turns, Identifier blockId) {
        for (ServerPlayerEntity player : world.getPlayers(player -> true)) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(pos);
            buf.writeByte(turns);
            buf.writeIdentifier(blockId);
            ServerPlayNetworking.send(player, UPDATE_PACKET, buf);
        }
    }

    private static PlacedOrientationState state(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                PlacedOrientationState::fromNbt, PlacedOrientationState::new, STATE_ID);
    }

    private static BlockState applyExplicitState(BlockState state, Direction desired) {
        BlockState result = state;
        boolean handled = false;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            if (name.equals("facing") || name.equals("horizontal_facing")) {
                BlockState changed = setParsed(result, property, desired.getName());
                if (changed != null) {
                    result = changed;
                    handled = true;
                }
            }
        }
        if (handled) return result;

        // A horizontal pillar/log axis has no sign; preserve vertical axis and map horizontal X/Z
        // to the player's chosen north axis.
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            if (!name.equals("axis") && !name.equals("horizontal_axis")) continue;
            String current = propertyValueName(state, property);
            if (current.equals("y")) continue;
            String target = desired.getAxis() == Direction.Axis.X ? "x" : "z";
            BlockState changed = setParsed(result, property, target);
            if (changed != null) return changed;
        }
        return result;
    }

    private static int quarterTurnsFromNorth(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private static BlockState setParsed(BlockState state, Property<?> property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isEmpty()) return null;
        return withRaw(state, property, (Comparable<?>) parsed.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withRaw(BlockState state, Property property, Comparable value) {
        return property.getValues().contains(value) ? state.with(property, value) : state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(BlockState state, Property property) {
        Comparable value = state.get(property);
        return property.name(value);
    }
}
