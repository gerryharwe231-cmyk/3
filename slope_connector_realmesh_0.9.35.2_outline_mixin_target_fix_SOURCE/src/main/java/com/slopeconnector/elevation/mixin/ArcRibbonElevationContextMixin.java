package com.slopeconnector.elevation.mixin;

import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.hotfix.ArcRibbonGenerator;
import com.slopeconnector.hotfix.SmoothElevationArcPath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Replaces only the two-point UP/DOWN elevation context; all planar arc math remains untouched. */
@Mixin(value = ArcRibbonGenerator.class, remap = false, priority = 2750)
abstract class ArcRibbonElevationContextMixin {
    @Inject(method = "twoPointContext", at = @At("RETURN"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$smoothElevation(BlockPos startBlock, BlockPos endBlock,
                                                               SlopeConnectorMod.PlayerSettings settings,
                                                               CallbackInfoReturnable<Object> cir) {
        Direction face = settings == null || settings.face == null ? Direction.UP : settings.face;
        if (face != Direction.UP && face != Direction.DOWN) return;
        if (startBlock.getY() == endBlock.getY()) return; // rejected earlier by the outer guard.

        Vec3d startCenter = Vec3d.ofCenter(startBlock);
        Vec3d endCenter = Vec3d.ofCenter(endBlock);
        Vec3d vertical = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
        Vec3d delta = endCenter.subtract(startCenter);
        double rise = delta.dotProduct(vertical);
        // Elevation geometry is determined by the actual signed height difference.  Do NOT fall
        // back to the embedded orthogonal elbow merely because the planar arc-side toggle points the
        // other way; that fallback was why one endpoint randomly became steep/vertical again.

        Vec3d horizontalDelta = delta.subtract(vertical.multiply(rise));
        double horizontalDistance = horizontalDelta.length();
        if (horizontalDistance < 1.05) return;
        Vec3d runDirection = horizontalDelta.multiply(1.0 / horizontalDistance);
        double startInset = rayCubeDistance(runDirection);
        double endInset = rayCubeDistance(runDirection.multiply(-1.0));
        double run = horizontalDistance - startInset - endInset;
        if (run < 0.05) return;

        Vec3d startConn = startCenter.add(runDirection.multiply(startInset));
        Vec3d width = runDirection.crossProduct(vertical);
        if (width.lengthSquared() < 1.0E-10) return;
        width = width.normalize();
        Object path = SmoothElevationArcPath.buildObject(run, rise);
        if (!SmoothElevationArcPath.isValid(path)) return;

        try {
            Class<?> pathResultClass = Class.forName("com.slopeconnector.hotfix.ArcPath$Result");
            Class<?> buildContextClass = Class.forName("com.slopeconnector.hotfix.ArcRibbonGenerator$BuildContext");
            Constructor<?> buildContextCtor = buildContextClass.getDeclaredConstructor(
                    Vec3d.class, Vec3d.class, Vec3d.class, Vec3d.class, pathResultClass, Vec3d.class);
            buildContextCtor.setAccessible(true);
            Object context = buildContextCtor.newInstance(startConn, runDirection, vertical, width, path, runDirection);

            Class<?> resultClass = Class.forName("com.slopeconnector.hotfix.ArcRibbonGenerator$ResultOrContext");
            Method ok = resultClass.getDeclaredMethod("ok", buildContextClass);
            ok.setAccessible(true);
            cir.setReturnValue(ok.invoke(null, context));
        } catch (ReflectiveOperationException ignored) {
            // Fail closed: if a future embedded core changes its private context shape, keep the
            // original 0.9.17 result instead of crashing world generation.
        }
    }

    private static double rayCubeDistance(Vec3d direction) {
        double max = Math.max(Math.abs(direction.x), Math.max(Math.abs(direction.y), Math.abs(direction.z)));
        return max < 1.0E-10 ? 0.0 : 0.5 / max;
    }
}
