package com.slopeconnector.surface.client.orientation;

import com.slopeconnector.surface.orientation.PlacedOrientationClientCache;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.function.Supplier;

/**
 * Visual Y-rotation for directionless blocks placed while view-orientation is enabled.
 * UVs are deliberately left attached to their vertices, so the texture rotates with the block.
 */
public final class ViewOrientationBakedModel extends ForwardingBakedModel {
    public ViewOrientationBakedModel(BakedModel wrapped) { this.wrapped = wrapped; }

    @Override public boolean isVanillaAdapter() { return false; }

    @Override
    public void emitBlockQuads(BlockRenderView blockView, BlockState state, BlockPos pos,
                               Supplier<Random> randomSupplier, RenderContext context) {
        int turns = PlacedOrientationClientCache.quarterTurns(pos, state);
        if (turns == 0) {
            super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
            return;
        }
        context.pushTransform(quad -> transform(quad, turns));
        try {
            super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
        } finally {
            context.popTransform();
        }
    }

    private static boolean transform(MutableQuadView quad, int turns) {
        turns = Math.floorMod(turns, 4);
        if (turns == 0) return true;
        for (int i = 0; i < 4; i++) {
            float x = quad.x(i), y = quad.y(i), z = quad.z(i);
            float rx, rz;
            switch (turns) {
                case 1 -> { rx = 1.0f - z; rz = x; }
                case 2 -> { rx = 1.0f - x; rz = 1.0f - z; }
                case 3 -> { rx = z; rz = 1.0f - x; }
                default -> { rx = x; rz = z; }
            }
            quad.pos(i, rx, y, rz);
            if (quad.hasNormal(i)) {
                float nx = quad.normalX(i), ny = quad.normalY(i), nz = quad.normalZ(i);
                float rnx, rnz;
                switch (turns) {
                    case 1 -> { rnx = -nz; rnz = nx; }
                    case 2 -> { rnx = -nx; rnz = -nz; }
                    case 3 -> { rnx = nz; rnz = -nx; }
                    default -> { rnx = nx; rnz = nz; }
                }
                quad.normal(i, rnx, ny, rnz);
            }
        }
        // Cache both directions before mutating either one. MutableQuadView.cullFace(...) may also
        // update nominalFace internally, so reading nominalFace after cullFace would rotate that face
        // twice and produce wrong face/texture orientation on some baked models.
        Direction originalCull = quad.cullFace();
        Direction originalNominal = quad.nominalFace();
        quad.cullFace(rotate(originalCull, turns));
        if (originalNominal != null) quad.nominalFace(rotate(originalNominal, turns));
        return true;
    }

    private static Direction rotate(Direction direction, int turns) {
        if (direction == null || direction.getAxis().isVertical()) return direction;
        Direction result = direction;
        for (int i = 0; i < turns; i++) {
            result = switch (result) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.NORTH;
                default -> result;
            };
        }
        return result;
    }
}
