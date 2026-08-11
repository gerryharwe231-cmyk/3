package com.slopeconnector.surface.mixin;

import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.hotfix.ArcRibbonGenerator;
import com.slopeconnector.hotfix.ArcAutoTrimSettings;
import com.slopeconnector.hotfix.FinalModelArcTrim;
import com.slopeconnector.surface.dimensions.ArcDimensionSettings;
import com.slopeconnector.model.ModelSystemMod;
import com.slopeconnector.model.ArcCollisionProxyBuilder;
import com.slopeconnector.model.ArcBuildGroupContext;
import com.slopeconnector.model.ArcEndpointMetadataBinder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the second independent cross-section dimension inside the original prism constructor.
 * The historic code name is upDown, but the G panel now calls this value "侧面宽度"; the embedded
 * original width field is displayed as "上下厚度".  Rendering, trimming and collision all consume
 * the same transformed prism geometry.
 */
@Pseudo
@Mixin(targets = "com.slopeconnector.hotfix.ArcRibbonGenerator", remap = false, priority = 2600)
public abstract class ArcRibbonDimensionMixin {
    private static final ThreadLocal<Double> SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE =
            ThreadLocal.withInitial(() -> 1.0);
    private static final ThreadLocal<Boolean> SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> SLOPECONNECTOR_SURFACE$DEFERRED_AUTO_TRIM =
            ThreadLocal.withInitial(() -> false);

    @Inject(method = "generate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$beginDimensions(World world, BlockPos startBlock,
                                                               BlockPos controlBlock, BlockPos endBlock,
                                                               BlockState source, @Coerce Object settings,
                                                               CallbackInfoReturnable<Object> cir) {
        double sideSpan = (double) ArcDimensionSettings.upDownForSettings(settings);
        boolean modelTemplate = source != null && ModelSystemMod.isModelHolder(source);
        SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.set(sideSpan);
        SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE.set(modelTemplate);

        // Explicitly reject the pathological plane requested by the user: with an UP/DOWN face and
        // equal endpoint Y, the embedded generator is asked to construct an arc in a degenerate
        // frame.  Reject BEFORE starting group tracking so a cancelled build cannot leave a stale
        // ThreadLocal that contaminates the next arc generation.
        if (settings instanceof SlopeConnectorMod.PlayerSettings playerSettings) {
            Direction face = playerSettings.face == null ? Direction.UP : playerSettings.face;
            if ((face == Direction.UP || face == Direction.DOWN)
                    && startBlock.getY() == endBlock.getY()) {
                SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.remove();
                SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE.remove();
                SLOPECONNECTOR_SURFACE$DEFERRED_AUTO_TRIM.remove();
                ArcBuildGroupContext.clear();
                cir.setReturnValue(new ArcRibbonGenerator.Result(0, 0, 0, 0,
                        "当前面为上/下且两个端点同高：连接失败，请改用侧向面或改变端点高度。"));
                return;
            }
            if (modelTemplate) {
                // Persist exact dimensions/orientation with the logical arc group.  This avoids
                // trying to infer a 3x3 cross-section later from individually clipped prism pieces.
                ArcBuildGroupContext.begin(Math.max(1, playerSettings.width), sideSpan, face);
            }
        } else if (modelTemplate) {
            ArcBuildGroupContext.begin(1.0, sideSpan, Direction.UP);
        }

        // The embedded auto-trimmer runs before this mixin has applied the final side/thickness
        // dimensions.  Defer it for ModelBlock arcs and rebuild once, from the final shared stations,
        // at RETURN.  This also lets the final pass cut stairs/slabs instead of full cubes only.
        // Remember whether a final trim pass is requested.  Do NOT toggle the global setting here:
        // a generator exception would otherwise leave auto-trim disabled for the rest of the session.
        // The redirect below suppresses only the embedded pre-dimension trim for this ModelBlock call.
        SLOPECONNECTOR_SURFACE$DEFERRED_AUTO_TRIM.set(modelTemplate && ArcAutoTrimSettings.enabled());
    }

    @Redirect(method = "generate",
            at = @At(value = "INVOKE",
                    target = "Lcom/slopeconnector/hotfix/ArcAutoTrimSettings;enabled()Z"),
            remap = false)
    private static boolean slopeconnectorSurface$deferEmbeddedAutoTrim() {
        return !SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE.get() && ArcAutoTrimSettings.enabled();
    }

    @Inject(method = "prism", at = @At("RETURN"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$repairCrossSection(Vec3d c0, Vec3d c1,
                                                                  Vec3d radial0, Vec3d radial1,
                                                                  Vec3d width,
                                                                  double width0, double width1,
                                                                  double normal0, double normal1,
                                                                  CallbackInfoReturnable<float[]> cir) {
        double scale = SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.get();
        boolean modelTemplate = SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE.get();
        if (!modelTemplate && Math.abs(scale - 1.0) < 1.0E-8) return;

        if (modelTemplate) {
            // The white Model Block is always a full 1x1x1 source cell.  Rebuild the prism from the
            // analytic frame instead of scaling whatever float vertices the old generator happened
            // to return.  Straight runs therefore remain real rectangular prisms instead of
            // collapsing to a thin line when radial/width frames become numerically skewed.
            Vec3d tangent = c1.subtract(c0);
            if (tangent.lengthSquared() > 1.0E-12) tangent = tangent.normalize();
            Vec3d w = orthogonal(width, tangent, new Vec3d(0, 0, 1));
            Vec3d r0 = crossSectionAxis(radial0, tangent, w);
            Vec3d r1 = crossSectionAxis(radial1, tangent, w);
            if (r0.dotProduct(r1) < 0.0) r1 = r1.multiply(-1.0);

            double wMin = width0, wMax = width1;
            if (wMax - wMin < 0.999) {
                double center = (wMin + wMax) * 0.5;
                wMin = center - 0.5;
                wMax = center + 0.5;
            }
            double nMin = normal0 * scale, nMax = normal1 * scale;
            if (nMax - nMin < 0.999 * scale) {
                double center = (nMin + nMax) * 0.5;
                nMin = center - 0.5 * scale;
                nMax = center + 0.5 * scale;
            }
            cir.setReturnValue(buildPrism(c0, c1, r0, r1, w, wMin, wMax, nMin, nMax));
            return;
        }

        float[] original = cir.getReturnValue();
        if (original == null || original.length != 24) return;
        float[] scaled = original.clone();
        for (int vertex = 0; vertex < 8; vertex++) {
            Vec3d center = vertex < 4 ? c0 : c1;
            Vec3d radial = vertex < 4 ? radial0 : radial1;
            if (radial.lengthSquared() < 1.0E-12) continue;
            radial = radial.normalize();
            int index = vertex * 3;
            Vec3d point = new Vec3d(scaled[index], scaled[index + 1], scaled[index + 2]);
            Vec3d relative = point.subtract(center);
            double component = relative.dotProduct(radial);
            Vec3d transformed = point.add(radial.multiply(component * (scale - 1.0)));
            scaled[index] = (float) transformed.x;
            scaled[index + 1] = (float) transformed.y;
            scaled[index + 2] = (float) transformed.z;
        }
        cir.setReturnValue(scaled);
    }

    private static Vec3d orthogonal(Vec3d axis, Vec3d tangent, Vec3d fallback) {
        Vec3d t = tangent == null || tangent.lengthSquared() < 1.0E-12
                ? new Vec3d(1, 0, 0) : tangent.normalize();
        Vec3d value = axis == null ? fallback : axis;
        value = value.subtract(t.multiply(value.dotProduct(t)));
        if (value.lengthSquared() < 1.0E-12) {
            // Pick the world axis least parallel to the segment and project it again.  Reusing a
            // parallel fallback here was one way a straight run could collapse into a line.
            Vec3d candidate = Math.abs(t.y) < 0.75 ? new Vec3d(0, 1, 0)
                    : (Math.abs(t.x) < 0.75 ? new Vec3d(1, 0, 0) : new Vec3d(0, 0, 1));
            value = candidate.subtract(t.multiply(candidate.dotProduct(t)));
        }
        return value.normalize();
    }

    private static Vec3d crossSectionAxis(Vec3d axis, Vec3d tangent, Vec3d width) {
        Vec3d value = orthogonal(axis, tangent, tangent.crossProduct(width));
        value = value.subtract(width.multiply(value.dotProduct(width)));
        if (value.lengthSquared() < 1.0E-12) value = tangent.crossProduct(width);
        return value.normalize();
    }

    private static float[] buildPrism(Vec3d c0, Vec3d c1, Vec3d r0, Vec3d r1, Vec3d w,
                                      double w0, double w1, double n0, double n1) {
        Vec3d[] vertices = new Vec3d[] {
                c0.add(w.multiply(w0)).add(r0.multiply(n0)),
                c0.add(w.multiply(w1)).add(r0.multiply(n0)),
                c0.add(w.multiply(w1)).add(r0.multiply(n1)),
                c0.add(w.multiply(w0)).add(r0.multiply(n1)),
                c1.add(w.multiply(w0)).add(r1.multiply(n0)),
                c1.add(w.multiply(w1)).add(r1.multiply(n0)),
                c1.add(w.multiply(w1)).add(r1.multiply(n1)),
                c1.add(w.multiply(w0)).add(r1.multiply(n1))
        };
        float[] out = new float[24];
        for (int i = 0; i < vertices.length; i++) {
            out[i * 3] = (float) vertices[i].x;
            out[i * 3 + 1] = (float) vertices[i].y;
            out[i * 3 + 2] = (float) vertices[i].z;
        }
        return out;
    }

    @Inject(method = "generate", at = @At("RETURN"), remap = false)
    private static void slopeconnectorSurface$finishDimensions(World world, BlockPos startBlock,
                                                                BlockPos controlBlock, BlockPos endBlock,
                                                                BlockState source, @Coerce Object settings,
                                                                CallbackInfoReturnable<?> cir) {
        try {
            removeEndpointOverlayHolders(world, startBlock, source);
            removeEndpointOverlayHolders(world, endBlock, source);
            if (source != null && ModelSystemMod.isModelHolder(source)
                    && ArcBuildGroupContext.active()) {
                long groupId = ArcBuildGroupContext.finishAndTag(world);
                if (groupId != 0L) {
                    Direction innerFace = settings instanceof SlopeConnectorMod.PlayerSettings playerSettings
                            && playerSettings.face != null ? playerSettings.face : Direction.UP;
                    ArcEndpointMetadataBinder.bind(world, startBlock, innerFace);
                    if (SLOPECONNECTOR_SURFACE$DEFERRED_AUTO_TRIM.get()) {
                        FinalModelArcTrim.rebuild(world, startBlock);
                    }
                    ArcCollisionProxyBuilder.rebuild(world, startBlock);
                }
            } else {
                ArcBuildGroupContext.clear();
            }
        } finally {
            ArcBuildGroupContext.clear();
            SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.remove();
            SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE.remove();
            SLOPECONNECTOR_SURFACE$DEFERRED_AUTO_TRIM.remove();
        }
    }

    /** Endpoint blocks now render natively, so obsolete overlay-only holders must be removed. */
    private static void removeEndpointOverlayHolders(World world, BlockPos endpoint, BlockState source) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = endpoint.add(dx, dy, dz);
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (!(blockEntity instanceof ArcRibbonBlockEntity ribbon)) continue;
                    if (!ribbon.getSourceState().equals(source) || ribbon.getSurfaces().isEmpty()) continue;
                    List<ArcRibbonBlockEntity.Prism> prisms = new ArrayList<>(ribbon.getPrisms());
                    if (prisms.isEmpty() && world.getBlockState(pos).getBlock() == ArcHotfixMod.ARC_RIBBON) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    } else {
                        ribbon.setData(ribbon.getSourceState(), prisms, List.of());
                        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                    }
                }
            }
        }
    }
}
