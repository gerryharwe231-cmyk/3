package com.slopeconnector.hotfix;

import java.util.ArrayList;
import java.util.List;

/**
 * True elevation/ramp path for two-point UP/DOWN connections.
 *
 * <p>Unlike the old straight -> quarter-circle -> vertical construction, this path owns a real
 * horizontal landing at BOTH endpoint blocks.  The middle transition is a C2 quintic smooth-step.
 * Therefore the first several generated sections and the last several generated sections are
 * physically horizontal, not merely "mathematically zero slope at one endpoint sample".</p>
 */
public final class SmoothElevationArcPath {
    private static final double EPS = 1.0E-7;
    private static final double SAMPLES_PER_BLOCK = 4.0;

    private SmoothElevationArcPath() {}

    public static Object buildObject(double run, double rise) { return build(run, rise); }
    public static boolean isValid(Object result) { return result instanceof ArcPath.Result path && path.valid(); }

    static ArcPath.Result build(double run, double rise) {
        if (!Double.isFinite(run) || !Double.isFinite(rise) || run < 0.05) {
            return ArcPath.Result.error("Z轴坡道的水平距离过短，无法生成平滑连接。");
        }

        // Explicit horizontal landing.  At normal road lengths each endpoint gets at least half a
        // block of truly level path; short runs shrink the landings instead of deleting the middle.
        double landing = Math.min(1.0, Math.max(0.50, run * 0.12));
        double minimumTransition = Math.min(0.75, Math.max(0.20, run * 0.30));
        if (run - 2.0 * landing < minimumTransition) {
            landing = Math.max(0.0, (run - minimumTransition) * 0.5);
        }
        double transitionStart = landing;
        double transitionEnd = run - landing;
        double transitionRun = transitionEnd - transitionStart;
        if (transitionRun < 0.05) {
            transitionStart = 0.0;
            transitionEnd = run;
            transitionRun = run;
        }

        double estimated = Math.hypot(run, rise);
        int segments = Math.max(16, Math.min(2048, (int)Math.ceil(estimated * SAMPLES_PER_BLOCK)));
        List<ArcPath.Sample> samples = new ArrayList<>(segments + 1);
        double distance = 0.0;
        double prevS = 0.0;
        double prevO = 0.0;

        for (int i = 0; i <= segments; i++) {
            double s = run * i / (double) segments;
            double h;
            double dhDs;
            if (s <= transitionStart + EPS) {
                h = 0.0;
                dhDs = 0.0;
            } else if (s >= transitionEnd - EPS) {
                h = 1.0;
                dhDs = 0.0;
            } else {
                double u = (s - transitionStart) / transitionRun;
                double u2 = u * u;
                double u3 = u2 * u;
                double u4 = u3 * u;
                double u5 = u4 * u;
                h = 6.0 * u5 - 15.0 * u4 + 10.0 * u3;
                // derivative of smooth-step with respect to WORLD horizontal distance s.
                dhDs = 30.0 * u2 * (u - 1.0) * (u - 1.0) / transitionRun;
            }

            double o = rise * h;
            double grade = rise * dhDs;
            double tangentLength = Math.hypot(1.0, grade);
            // ArcPath stores the cross-section normal (ns,no), perpendicular to tangent (1,grade).
            double ns = -grade / tangentLength;
            double no = 1.0 / tangentLength;

            if (i > 0) distance += Math.hypot(s - prevS, o - prevO);
            samples.add(new ArcPath.Sample(s, o, ns, no, distance));
            prevS = s;
            prevO = o;
        }
        return new ArcPath.Result(List.copyOf(samples), "");
    }
}
