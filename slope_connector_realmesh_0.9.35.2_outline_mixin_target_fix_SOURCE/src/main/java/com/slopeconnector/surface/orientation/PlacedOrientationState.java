package com.slopeconnector.surface.orientation;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-persistent visual quarter-turns for blocks whose BlockState has no horizontal orientation. */
public final class PlacedOrientationState extends PersistentState {
    private static final String ROOT = "Entries";
    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    public record Entry(int quarterTurns, Identifier blockId) {}

    public static PlacedOrientationState fromNbt(NbtCompound nbt) {
        PlacedOrientationState state = new PlacedOrientationState();
        NbtList list = nbt.getList(ROOT, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompound(i);
            Identifier id = Identifier.tryParse(item.getString("Block"));
            if (id == null) continue;
            int turns = Math.floorMod(item.getByte("Turns"), 4);
            if (turns == 0) continue;
            state.entries.put(item.getLong("Pos"), new Entry(turns, id));
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<Long, Entry> entry : entries.entrySet()) {
            NbtCompound item = new NbtCompound();
            item.putLong("Pos", entry.getKey());
            item.putByte("Turns", (byte) entry.getValue().quarterTurns());
            item.putString("Block", entry.getValue().blockId().toString());
            list.add(item);
        }
        nbt.put(ROOT, list);
        return nbt;
    }

    public void put(BlockPos pos, int turns, Identifier blockId) {
        turns = Math.floorMod(turns, 4);
        if (turns == 0) entries.remove(pos.asLong());
        else entries.put(pos.asLong(), new Entry(turns, blockId));
        markDirty();
    }

    public void remove(BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) markDirty();
    }

    public Map<Long, Entry> entries() { return Map.copyOf(entries); }
}
