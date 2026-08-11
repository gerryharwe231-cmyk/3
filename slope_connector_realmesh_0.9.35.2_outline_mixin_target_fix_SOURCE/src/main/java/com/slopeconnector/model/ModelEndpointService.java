package com.slopeconnector.model;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** Converts only actual arc endpoints from the cheap public ModelBlock into the internal BE holder. */
public final class ModelEndpointService {
    private ModelEndpointService() {}

    public static ModelBlockEntity ensureEndpoint(World world, BlockPos pos, Direction innerFace) {
        if (world == null || pos == null) return null;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() == ModelSystemMod.MODEL_BLOCK) {
            state = ModelSystemMod.MODEL_ENDPOINT_BLOCK.getDefaultState();
            world.setBlockState(pos, state, 2);
        } else if (state.getBlock() != ModelSystemMod.MODEL_ENDPOINT_BLOCK) {
            return null;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        ModelBlockEntity model;
        if (blockEntity instanceof ModelBlockEntity existing) {
            model = existing;
        } else {
            model = new ModelBlockEntity(pos, state);
            world.addBlockEntity(model);
        }
        model.setArcMetadata(model.getArcDirection(), innerFace == null ? Direction.UP : innerFace);
        return model;
    }

    public static ModelBlockEntity ensureSkinned(World world, BlockPos pos) {
        if (world == null || pos == null) return null;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() == ModelSystemMod.MODEL_BLOCK) {
            state = ModelSystemMod.MODEL_ENDPOINT_BLOCK.getDefaultState();
            world.setBlockState(pos, state, 2);
        } else if (state.getBlock() != ModelSystemMod.MODEL_ENDPOINT_BLOCK) {
            return null;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof ModelBlockEntity model) return model;
        ModelBlockEntity model = new ModelBlockEntity(pos, state);
        world.addBlockEntity(model);
        return model;
    }
}
