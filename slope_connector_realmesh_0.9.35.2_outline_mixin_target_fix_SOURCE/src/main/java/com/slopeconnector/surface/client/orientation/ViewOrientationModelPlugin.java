package com.slopeconnector.surface.client.orientation;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;

/** Installs the visual-orientation wrapper on block-state models only, never inventory models. */
public final class ViewOrientationModelPlugin {
    private static boolean registered;

    private ViewOrientationModelPlugin() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ModelLoadingPlugin.register(context -> context.modifyModelAfterBake().register((model, modifyContext) -> {
            if (model == null || model instanceof ViewOrientationBakedModel) return model;
            if (!(modifyContext.id() instanceof ModelIdentifier id)) return model;
            if ("inventory".equals(id.getVariant())) return model;
            return new ViewOrientationBakedModel(model);
        }));
    }
}
