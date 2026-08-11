package com.slopeconnector.hotfix;

public final class SmoothElevationHarness {
    public static void main(String[] args) {
        ArcPath.Result result = SmoothElevationArcPath.build(12.0, 4.0);
        if (!result.valid() || result.samples().size() < 16) throw new AssertionError("invalid smooth elevation result");
        ArcPath.Sample first = result.samples().get(0);
        ArcPath.Sample second = result.samples().get(1);
        ArcPath.Sample third = result.samples().get(2);
        int lastIndex = result.samples().size() - 1;
        ArcPath.Sample last = result.samples().get(lastIndex);
        ArcPath.Sample beforeLast = result.samples().get(lastIndex - 1);
        ArcPath.Sample beforeBeforeLast = result.samples().get(lastIndex - 2);
        if (Math.abs(first.s()) > 1e-9 || Math.abs(first.o()) > 1e-9) throw new AssertionError("bad start");
        if (Math.abs(last.s() - 12.0) > 1e-6 || Math.abs(last.o() - 4.0) > 1e-6) throw new AssertionError("bad end");

        // A visible landing means several actual geometry samples are level, not just one derivative.
        if (Math.abs(second.o()) > 1e-9 || Math.abs(third.o()) > 1e-9)
            throw new AssertionError("start landing is not physically level");
        if (Math.abs(beforeLast.o() - 4.0) > 1e-9 || Math.abs(beforeBeforeLast.o() - 4.0) > 1e-9)
            throw new AssertionError("end landing is not physically level");
        for (ArcPath.Sample sample : new ArcPath.Sample[]{first,second,third,beforeBeforeLast,beforeLast,last}) {
            if (Math.abs(sample.ns()) > 1e-8 || sample.no() < 0.999999)
                throw new AssertionError("landing normal is not vertical / tangent not level");
        }

        // The middle is still a real grade and scales from rise / usable run.
        ArcPath.Sample middle = result.samples().get(result.samples().size()/2);
        if (Math.abs(middle.ns()) < 0.05) throw new AssertionError("middle never develops a slope");

        double previous = -1.0;
        for (ArcPath.Sample sample : result.samples()) {
            if (sample.distance() + 1e-9 < previous) throw new AssertionError("non-monotonic distance");
            previous = sample.distance();
        }
        System.out.println("smooth elevation physical landing harness passed");
    }
}
