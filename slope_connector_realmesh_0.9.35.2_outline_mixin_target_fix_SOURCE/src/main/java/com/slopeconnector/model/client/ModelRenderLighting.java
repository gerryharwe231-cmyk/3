package com.slopeconnector.model.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import org.joml.Vector3f;

/** Shared light sampler for both curved middle models and endpoint ModelBlocks. */
final class ModelRenderLighting {
    private ModelRenderLighting() {}

    static int sample(BlockRenderView world, BlockState state, Vec3d worldCenter,
                      Vector3f normal, int fallback) {
        int best = maxPacked(fallback,
                WorldRenderer.getLightmapCoordinates(world, state, BlockPos.ofFloored(worldCenter)));
        Vec3d openSide = worldCenter.add(normal.x * 0.58, normal.y * 0.58, normal.z * 0.58);
        best = maxPacked(best,
                WorldRenderer.getLightmapCoordinates(world, state, BlockPos.ofFloored(openSide)));
        return best;
    }

    static int maxPacked(int first, int second) {
        int block = Math.max(LightmapTextureManager.getBlockLightCoordinates(first),
                LightmapTextureManager.getBlockLightCoordinates(second));
        int sky = Math.max(LightmapTextureManager.getSkyLightCoordinates(first),
                LightmapTextureManager.getSkyLightCoordinates(second));
        return LightmapTextureManager.pack(block, sky);
    }
}
