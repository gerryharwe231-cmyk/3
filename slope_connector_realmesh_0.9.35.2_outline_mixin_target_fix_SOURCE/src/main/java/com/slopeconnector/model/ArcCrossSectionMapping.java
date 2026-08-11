package com.slopeconnector.model;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * One shared mapping from ArcStation width/radial axes to a captured model's local Z/Y axes.
 * Both the curved middle renderer and the two endpoint renderers must use this exact rule; keeping
 * separate copies was what let stair/texture orientation drift apart again at the terminal block.
 */
public final class ArcCrossSectionMapping {
    private static final double EPS = 1.0E-10;

    public record Mapping(Vec3d lateral, Vec3d vertical,
                          double lateralSpan, double verticalSpan,
                          boolean lateralUsesWidth, double lateralSign, double verticalSign) {}

    private ArcCrossSectionMapping() {}

    public static Mapping resolve(Vec3d width, Vec3d radial,
                                  double widthSpan, double radialSpan,
                                  Direction innerDirection) {
        Vec3d w = normalized(width, new Vec3d(0,0,1));
        Vec3d r = normalized(radial, new Vec3d(0,1,0));
        Vec3d preferredInner = innerDirection == null ? null
                : new Vec3d(innerDirection.getOffsetX(), innerDirection.getOffsetY(), innerDirection.getOffsetZ());

        boolean lateralUsesWidth;
        double lateralSign;
        double verticalSign;
        if (preferredInner != null && preferredInner.lengthSquared() > EPS) {
            lateralUsesWidth = Math.abs(w.dotProduct(preferredInner)) >= Math.abs(r.dotProduct(preferredInner));
            Vec3d lateral = lateralUsesWidth ? w : r;
            Vec3d vertical = lateralUsesWidth ? r : w;
            lateralSign = lateral.dotProduct(preferredInner) < 0.0 ? -1.0 : 1.0;
            Vec3d up = new Vec3d(0,1,0);
            verticalSign = Math.abs(vertical.dotProduct(up)) > 0.15 && vertical.dotProduct(up) < 0.0 ? -1.0 : 1.0;
        } else {
            Vec3d up = new Vec3d(0,1,0);
            boolean verticalUsesWidth = Math.abs(w.dotProduct(up)) >= Math.abs(r.dotProduct(up));
            lateralUsesWidth = !verticalUsesWidth;
            Vec3d vertical = verticalUsesWidth ? w : r;
            lateralSign = 1.0;
            verticalSign = vertical.dotProduct(up) < 0.0 ? -1.0 : 1.0;
        }

        Vec3d lateral = (lateralUsesWidth ? w : r).multiply(lateralSign);
        Vec3d vertical = (lateralUsesWidth ? r : w).multiply(verticalSign);
        double lateralSpan = lateralUsesWidth ? widthSpan : radialSpan;
        double verticalSpan = lateralUsesWidth ? radialSpan : widthSpan;
        return new Mapping(lateral, vertical,
                Math.max(1.0, lateralSpan), Math.max(1.0, verticalSpan),
                lateralUsesWidth, lateralSign, verticalSign);
    }

    private static Vec3d normalized(Vec3d value, Vec3d fallback) {
        return value == null || value.lengthSquared() < EPS ? fallback : value.normalize();
    }
}
