package com.slopeconnector.model;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/** Internal endpoint holder. No item is registered for this block. */
public final class ModelEndpointBlock extends BlockWithEntity {
    public ModelEndpointBlock(Settings settings) {
        super(settings);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ModelBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (world.getBlockEntity(pos) instanceof ModelBlockEntity model && model.isSkinned()) {
            try { return repeatedEndpointShape(model.getDisplayState().getOutlineShape(world, pos, context), model); }
            catch (RuntimeException ignored) { }
        }
        if (world.getBlockEntity(pos) instanceof ModelBlockEntity model) {
            return repeatedEndpointShape(VoxelShapes.fullCube(), model);
        }
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape source = VoxelShapes.fullCube();
        if (world.getBlockEntity(pos) instanceof ModelBlockEntity model) {
            if (model.isSkinned()) {
                try { source = model.getDisplayState().getCollisionShape(world, pos, context); }
                catch (RuntimeException ignored) { }
            }
            return repeatedEndpointShape(source, model);
        }
        return source;
    }

    @Override
    public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.empty();
    }

    @Override
    public int getOpacity(BlockState state, BlockView world, BlockPos pos) {
        return 0;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                 WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (world.getBlockEntity(pos) instanceof ModelBlockEntity model) model.onNeighborChanged();
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    private static VoxelShape repeatedEndpointShape(VoxelShape source, ModelBlockEntity model) {
        int widthTiles = Math.max(1, Math.min(32, (int)Math.round(model.getEndpointWidthSpan())));
        int radialTiles = Math.max(1, Math.min(32, (int)Math.round(model.getEndpointRadialSpan())));
        if (widthTiles == 1 && radialTiles == 1) return source;
        Vec3d w = model.getEndpointWidthAxis();
        Vec3d r = model.getEndpointRadialAxis();
        if (w.lengthSquared() < 1.0E-8) w = new Vec3d(0, 0, 1); else w = w.normalize();
        if (r.lengthSquared() < 1.0E-8) r = new Vec3d(0, 1, 0); else r = r.normalize();
        double wCell = model.getEndpointWidthSpan() / widthTiles;
        double rCell = model.getEndpointRadialSpan() / radialTiles;
        VoxelShape result = VoxelShapes.empty();
        for (int wi = 0; wi < widthTiles; wi++) {
            for (int ri = 0; ri < radialTiles; ri++) {
                double wo = ((wi + 0.5) - widthTiles * 0.5) * wCell;
                double ro = ((ri + 0.5) - radialTiles * 0.5) * rCell;
                Vec3d offset = w.multiply(wo).add(r.multiply(ro));
                result = VoxelShapes.union(result, source.offset(offset.x, offset.y, offset.z));
            }
        }
        return result.simplify();
    }
}
