package com.slopeconnector.model;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * One canonical cross-section frame for every topology node of an ArcRibbon component.
 *
 * <p>Adjacent prisms used to keep two independent end frames.  On a bend the inner corners overlap,
 * while the outer corners diverge, which is why the user's cracks appeared almost exclusively on the
 * outer arc.  This class averages the two geometric measurements once at the shared node and both
 * neighboring segments consume that exact same station.</p>
 */
public final class ArcStationFrames {
    private static final double EPS = 1.0E-10;

    public record Station(Vec3d center, Vec3d tangent, Vec3d width, Vec3d radial,
                          double widthSpan, double radialSpan) {}

    private ArcStationFrames() {}

    public static List<Station> build(ArcComponentFinder.Component component) {
        List<ArcComponentFinder.Segment> segments = component.segments();
        if (segments.isEmpty()) return List.of();
        List<Station> out = new ArrayList<>(segments.size() + 1);
        for (int node = 0; node <= segments.size(); node++) {
            ArcComponentFinder.Segment previous = node > 0 ? segments.get(node - 1) : null;
            ArcComponentFinder.Segment next = node < segments.size() ? segments.get(node) : null;
            Vec3d center = previous == null ? next.c0() : previous.c1();
            Vec3d tangent = tangent(previous, next);

            Vec3d widthA = previous == null ? null : previous.width1();
            Vec3d widthB = next == null ? null : next.width0();
            Vec3d radialA = previous == null ? null : previous.radial1();
            Vec3d radialB = next == null ? null : next.radial0();
            Vec3d width = averageAxis(widthA, widthB, tangent, null);
            Vec3d radial = averageAxis(radialA, radialB, tangent, width);

            // Gram-Schmidt on the one shared station frame.  The measured radial direction decides
            // the sign, so the inner/outer side cannot silently flip at a straight/arc transition.
            width = project(width, tangent, fallback(tangent)).normalize();
            radial = project(radial, tangent, tangent.crossProduct(width));
            radial = radial.subtract(width.multiply(radial.dotProduct(width)));
            if (radial.lengthSquared() < EPS) radial = tangent.crossProduct(width);
            radial = radial.normalize();
            Vec3d measuredRadial = alignedAverage(radialA, radialB);
            if (measuredRadial != null && radial.dotProduct(measuredRadial) < 0.0) radial = radial.multiply(-1.0);

            double widthSpan = averageSpan(previous == null ? Double.NaN : previous.widthSpan1(),
                    next == null ? Double.NaN : next.widthSpan0());
            double radialSpan = averageSpan(previous == null ? Double.NaN : previous.radialSpan1(),
                    next == null ? Double.NaN : next.radialSpan0());
            // 0.9.31: exact user-selected dimensions are persisted with the logical arc group.
            // Prefer them over reconstructing width/thickness from clipped holder fragments.
            if (Double.isFinite(component.exactWidthSpan()) && component.exactWidthSpan() >= 1.0) {
                widthSpan = component.exactWidthSpan();
            }
            if (Double.isFinite(component.exactRadialSpan()) && component.exactRadialSpan() >= 1.0) {
                radialSpan = component.exactRadialSpan();
            }
            out.add(new Station(center, tangent, width, radial,
                    Math.max(1.0E-4, widthSpan), Math.max(1.0E-4, radialSpan)));
        }
        return List.copyOf(out);
    }

    /**
     * Rebuilds an endpoint station on the endpoint ModelBlock's axis-aligned connection plane.
     * The centerline is untouched; only the cross-section frame is aligned.  This replaces the old
     * per-vertex miter projection, which could leave an outer gap while the inner side overlapped
     * when an endpoint entered a curve immediately.
     */
    public static Station alignEndpoint(Station station, ModelBlockEntity endpoint) {
        if (station == null || endpoint == null || endpoint.getArcDirection() == null) return station;
        Vec3d axis = new Vec3d(endpoint.getArcDirection().getOffsetX(),
                endpoint.getArcDirection().getOffsetY(), endpoint.getArcDirection().getOffsetZ());
        if (axis.lengthSquared() < EPS) return station;
        axis = axis.normalize();
        // Keep the canonical start->end order. At the terminal endpoint arcDirection points back
        // into the arc, so its normal must be reversed to agree with the ordered tangent.
        if (axis.dotProduct(station.tangent()) < 0.0) axis = axis.multiply(-1.0);

        Vec3d width = project(station.width(), axis, fallback(axis));
        if (width.lengthSquared() < EPS) width = fallback(axis);
        width = width.normalize();
        Vec3d radial = project(station.radial(), axis, axis.crossProduct(width));
        radial = radial.subtract(width.multiply(radial.dotProduct(width)));
        if (radial.lengthSquared() < EPS) radial = axis.crossProduct(width);
        radial = radial.normalize();
        if (radial.dotProduct(station.radial()) < 0.0) radial = radial.multiply(-1.0);

        // The arc must meet the actual endpoint block face.  Previously only the axes were aligned
        // while the station center remained at the old sampled point, leaving a visible terminal
        // hole that still had collision.  arcDirection always points from the endpoint into the arc.
        Vec3d endpointCenter = Vec3d.ofCenter(endpoint.getPos());
        Vec3d faceCenter = endpointCenter.add(new Vec3d(
                endpoint.getArcDirection().getOffsetX(), endpoint.getArcDirection().getOffsetY(),
                endpoint.getArcDirection().getOffsetZ()).multiply(0.5));
        return new Station(faceCenter, axis, width, radial,
                station.widthSpan(), station.radialSpan());
    }

    public static Vec3d[] section(Station station) {
        Vec3d w = station.width().multiply(station.widthSpan() * 0.5);
        Vec3d r = station.radial().multiply(station.radialSpan() * 0.5);
        Vec3d c = station.center();
        return new Vec3d[] {
                c.subtract(w).subtract(r),
                c.add(w).subtract(r),
                c.add(w).add(r),
                c.subtract(w).add(r)
        };
    }

    private static Vec3d tangent(ArcComponentFinder.Segment previous, ArcComponentFinder.Segment next) {
        Vec3d a = previous == null ? null : direction(previous);
        Vec3d b = next == null ? null : direction(next);
        if (a == null) return b == null ? new Vec3d(1, 0, 0) : b;
        if (b == null) return a;
        Vec3d sum = a.add(b);
        if (sum.lengthSquared() < EPS) return b;
        return sum.normalize();
    }

    private static Vec3d direction(ArcComponentFinder.Segment segment) {
        Vec3d value = segment.c1().subtract(segment.c0());
        return value.lengthSquared() < EPS ? new Vec3d(1, 0, 0) : value.normalize();
    }

    private static Vec3d averageAxis(Vec3d first, Vec3d second, Vec3d tangent, Vec3d exclude) {
        Vec3d value = alignedAverage(first, second);
        if (value == null) value = exclude == null ? fallback(tangent) : tangent.crossProduct(exclude);
        value = project(value, tangent, fallback(tangent));
        if (exclude != null) {
            value = value.subtract(exclude.multiply(value.dotProduct(exclude)));
            if (value.lengthSquared() < EPS) value = tangent.crossProduct(exclude);
        }
        return value.lengthSquared() < EPS ? fallback(tangent) : value.normalize();
    }

    private static Vec3d alignedAverage(Vec3d first, Vec3d second) {
        if (first == null && second == null) return null;
        if (first == null) return second;
        if (second == null) return first;
        Vec3d b = second;
        if (first.dotProduct(b) < 0.0) b = b.multiply(-1.0);
        Vec3d sum = first.add(b);
        return sum.lengthSquared() < EPS ? first : sum.normalize();
    }

    private static Vec3d project(Vec3d value, Vec3d tangent, Vec3d fallback) {
        Vec3d result = value.subtract(tangent.multiply(value.dotProduct(tangent)));
        if (result.lengthSquared() >= EPS) return result;
        result = fallback.subtract(tangent.multiply(fallback.dotProduct(tangent)));
        return result.lengthSquared() < EPS ? new Vec3d(0, 1, 0) : result;
    }

    private static Vec3d fallback(Vec3d tangent) {
        Vec3d axis = Math.abs(tangent.y) < 0.75 ? new Vec3d(0, 1, 0)
                : (Math.abs(tangent.x) < 0.75 ? new Vec3d(1, 0, 0) : new Vec3d(0, 0, 1));
        return project(axis, tangent, new Vec3d(0, 0, 1)).normalize();
    }

    private static double averageSpan(double a, double b) {
        boolean aOk = Double.isFinite(a) && a > 1.0E-6;
        boolean bOk = Double.isFinite(b) && b > 1.0E-6;
        if (aOk && bOk) return (a + b) * 0.5;
        if (aOk) return a;
        if (bOk) return b;
        return 1.0;
    }
}
