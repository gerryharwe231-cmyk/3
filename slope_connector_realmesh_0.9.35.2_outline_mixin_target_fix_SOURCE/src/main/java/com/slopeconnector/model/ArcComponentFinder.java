package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.surface.geometry.SegmentChainOrder;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconstructs one connected ArcRibbon component from real prism centreline geometry. */
public final class ArcComponentFinder {
    public static final double JOIN_EPS = 0.22;
    public static final double TOPOLOGY_ENDPOINT_EPS = 0.08;
    private static final int DISCOVERY_RADIUS_XY = 3;
    private static final int DISCOVERY_RADIUS_Z = 6;
    private static final int ENDPOINT_SEARCH_RADIUS_XY = 3;
    private static final int ENDPOINT_SEARCH_RADIUS_Z = 8;
    private static final int MAX_ENTITIES = 8192;
    private static final double REPRESENTATIVE_BIN = 0.125;
    private static final double EPS = 1.0E-8;

    private ArcComponentFinder() {}

    public static Component fromClickedModelBlock(World world, BlockPos endpoint) {
        ArcRibbonBlockEntity nearest = null;
        double best = Double.POSITIVE_INFINITY;
        Vec3d center = Vec3d.ofCenter(endpoint);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -ENDPOINT_SEARCH_RADIUS_Z; dz <= ENDPOINT_SEARCH_RADIUS_Z; dz++) {
                    BlockEntity be = world.getBlockEntity(endpoint.add(dx, dy, dz));
                    if (!(be instanceof ArcRibbonBlockEntity ribbon)) continue;
                    for (RawSegment segment : rawSegments(ribbon)) {
                        double value = Math.min(segment.c0.squaredDistanceTo(center),
                                segment.c1.squaredDistanceTo(center));
                        if (value < best) {
                            best = value;
                            nearest = ribbon;
                        }
                    }
                }
            }
        }
        if (nearest == null) return null;
        Component component = build(nearest);
        return orientToPreferredEndpoint(component, endpoint);
    }

    public static Component build(ArcRibbonBlockEntity seed) {
        if (seed == null || seed.getWorld() == null) return null;
        World world = seed.getWorld();
        long seedGroupId = ArcPrismTags.groupId(seed);
        if (rawSegments(seed).isEmpty()) {
            ArcRibbonBlockEntity nearby = seedGroupId != 0L
                    ? nearestGroupTopologyRibbon(world, seed, seedGroupId)
                    : nearestTopologyRibbon(world, seed.getPos());
            if (nearby == null) return null;
            seed = nearby;
            seedGroupId = ArcPrismTags.groupId(seed);
        }
        long groupId = seedGroupId;
        List<ArcRibbonBlockEntity> members = discover(world, seed);
        if (members.isEmpty()) return null;

        // Width/thickness expansion may split one logical longitudinal sample across several holder
        // BEs and several parallel prism pieces.  Merge the ENTIRE logical group first; doing this
        // per holder was the reason 0.9.30 could discover only a low/middle/high strip at a time.
        List<RawSegment> candidates = new ArrayList<>();
        for (ArcRibbonBlockEntity member : members) candidates.addAll(rawSegments(member));
        double exactWidthSpan = firstFiniteGroupWidth(members);
        double exactRadialSpan = firstFiniteGroupRadial(members);
        Direction innerFace = firstGroupInnerFace(members);
        List<RawSegment> raw = representativeSegments(candidates, exactWidthSpan, exactRadialSpan);
        List<Segment> ordered = order(raw);
        if (ordered.isEmpty()) return null;
        ArcRibbonBlockEntity leader = ordered.get(0).owner;
        BlockPos startModel = nearestModelBlock(world, ordered.get(0).c0);
        BlockPos endModel = nearestModelBlock(world, ordered.get(ordered.size() - 1).c1);
        Component component = new Component(world, List.copyOf(members), List.copyOf(ordered),
                leader, startModel, endModel, groupId, exactWidthSpan, exactRadialSpan, innerFace);
        return orientToStoredEndpointDirection(component);
    }

    private static ArcRibbonBlockEntity nearestTopologyRibbon(World world, BlockPos base) {
        ArcRibbonBlockEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockEntity be = world.getBlockEntity(base.add(dx, dy, dz));
                    if (!(be instanceof ArcRibbonBlockEntity ribbon) || rawSegments(ribbon).isEmpty()) continue;
                    int ddx = base.getX() - ribbon.getPos().getX();
                    int ddy = base.getY() - ribbon.getPos().getY();
                    int ddz = base.getZ() - ribbon.getPos().getZ();
                    double d = (double) ddx * ddx + (double) ddy * ddy + (double) ddz * ddz;
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = ribbon;
                    }
                }
            }
        }
        return best;
    }

    private static ArcRibbonBlockEntity nearestGroupTopologyRibbon(World world,
                                                                  ArcRibbonBlockEntity proxySeed,
                                                                  long groupId) {
        double span=Math.max(ArcPrismTags.groupWidthSpan(proxySeed), ArcPrismTags.groupRadialSpan(proxySeed));
        int radius=Math.max(4, Math.min(24, (int)Math.ceil((Double.isFinite(span)?span:4.0)*0.5)+4));
        BlockPos base=proxySeed.getPos();
        ArcRibbonBlockEntity best=null; double bestDistance=Double.POSITIVE_INFINITY;
        for(int dx=-radius;dx<=radius;dx++) for(int dy=-radius;dy<=radius;dy++) for(int dz=-radius;dz<=radius;dz++) {
            BlockEntity be=world.getBlockEntity(base.add(dx,dy,dz));
            if(!(be instanceof ArcRibbonBlockEntity ribbon) || ArcPrismTags.isProxyOnly(ribbon)) continue;
            if(ArcPrismTags.groupId(ribbon)!=groupId || rawSegments(ribbon).isEmpty()) continue;
            double d=(double)dx*dx+(double)dy*dy+(double)dz*dz;
            if(d<bestDistance){bestDistance=d;best=ribbon;}
        }
        return best;
    }

    private static List<ArcRibbonBlockEntity> discover(World world, ArcRibbonBlockEntity seed) {
        List<ArcRibbonBlockEntity> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<ArcRibbonBlockEntity> queue = new ArrayDeque<>();
        long logicalGroup = ArcPrismTags.groupId(seed);
        queue.add(seed);
        visited.add(seed.getPos());
        while (!queue.isEmpty() && result.size() < MAX_ENTITIES) {
            ArcRibbonBlockEntity current = queue.removeFirst();
            if (!ArcPrismTags.isProxyOnly(current)) result.add(current);
            BlockPos base = current.getPos();
            for (int dx = -DISCOVERY_RADIUS_XY; dx <= DISCOVERY_RADIUS_XY; dx++) {
                for (int dy = -DISCOVERY_RADIUS_XY; dy <= DISCOVERY_RADIUS_XY; dy++) {
                    for (int dz = -DISCOVERY_RADIUS_Z; dz <= DISCOVERY_RADIUS_Z; dz++) {
                        BlockPos pos = base.add(dx, dy, dz);
                        if (visited.contains(pos)) continue;
                        BlockEntity be = world.getBlockEntity(pos);
                        if (!(be instanceof ArcRibbonBlockEntity other)) continue;
                        if (ArcPrismTags.isProxyOnly(other)) continue;
                        if (logicalGroup != 0L) {
                            if (ArcPrismTags.groupId(other) != logicalGroup) continue;
                            visited.add(pos);
                            queue.add(other);
                            continue;
                        }
                        if (!touches(current, other)) continue;
                        visited.add(pos);
                        queue.add(other);
                    }
                }
            }
        }
        return result;
    }

    private static boolean touches(ArcRibbonBlockEntity a, ArcRibbonBlockEntity b) {
        double limit = TOPOLOGY_ENDPOINT_EPS * TOPOLOGY_ENDPOINT_EPS;
        List<RawSegment> aa = rawSegments(a);
        List<RawSegment> bb = rawSegments(b);
        for (RawSegment x : aa) {
            for (RawSegment y : bb) {
                if (x.c0.squaredDistanceTo(y.c0) <= limit || x.c0.squaredDistanceTo(y.c1) <= limit
                        || x.c1.squaredDistanceTo(y.c0) <= limit || x.c1.squaredDistanceTo(y.c1) <= limit) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<RawSegment> rawSegments(ArcRibbonBlockEntity entity) {
        List<RawSegment> candidates = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (ArcPrismTags.isMetadata(prism)) continue;
            float[] v = prism.xyz();
            if (v == null || v.length < 24) continue;
            Vec3d c0 = average(entity.getPos(), v, 0, 4);
            Vec3d c1 = average(entity.getPos(), v, 4, 8);
            if (c0.squaredDistanceTo(c1) < EPS) continue;
            Vec3d widthVector0 = edge(v, 0, 1).add(edge(v, 3, 2)).multiply(0.5);
            Vec3d widthVector1 = edge(v, 4, 5).add(edge(v, 7, 6)).multiply(0.5);
            Vec3d radialVector0 = edge(v, 0, 3).add(edge(v, 1, 2)).multiply(0.5);
            Vec3d radialVector1 = edge(v, 4, 7).add(edge(v, 5, 6)).multiply(0.5);
            double widthSpan0 = widthVector0.length();
            double widthSpan1 = widthVector1.length();
            double radialSpan0 = radialVector0.length();
            double radialSpan1 = radialVector1.length();
            Vec3d width0 = widthSpan0 < EPS ? new Vec3d(0, 0, 1) : widthVector0.multiply(1.0 / widthSpan0);
            Vec3d width1 = widthSpan1 < EPS ? width0 : widthVector1.multiply(1.0 / widthSpan1);
            Vec3d radial0 = radialSpan0 < EPS ? new Vec3d(0, 1, 0) : radialVector0.multiply(1.0 / radialSpan0);
            Vec3d radial1 = radialSpan1 < EPS ? radial0 : radialVector1.multiply(1.0 / radialSpan1);
            candidates.add(new RawSegment(entity, c0, c1, width0, width1, radial0, radial1,
                    widthSpan0, widthSpan1, radialSpan0, radialSpan1));
        }
        return candidates;
    }

    private static List<RawSegment> representativeSegments(List<RawSegment> candidates,
                                                           double exactWidthSpan,
                                                           double exactRadialSpan) {
        if (candidates.isEmpty()) return List.of();
        Map<SegmentKey, List<RawSegment>> groups = new LinkedHashMap<>();
        for (RawSegment candidate : candidates) {
            groups.computeIfAbsent(segmentKey(candidate), ignored -> new ArrayList<>()).add(candidate);
        }
        List<RawSegment> merged = new ArrayList<>();
        double clusterRadius = crossSectionClusterRadius(exactWidthSpan, exactRadialSpan);
        for (List<RawSegment> bucket : groups.values()) {
            for (List<RawSegment> cluster : spatialClusters(bucket, clusterRadius)) {
                merged.add(mergeGroup(cluster, exactWidthSpan, exactRadialSpan));
            }
        }
        return merged;
    }

    private static double crossSectionClusterRadius(double widthSpan, double radialSpan) {
        double w=Double.isFinite(widthSpan)?Math.max(1.0,widthSpan):1.0;
        double r=Double.isFinite(radialSpan)?Math.max(1.0,radialSpan):1.0;
        return Math.max(1.15, Math.hypot(w,r)*0.58);
    }

    private static List<List<RawSegment>> spatialClusters(List<RawSegment> input, double radius) {
        List<List<RawSegment>> clusters=new ArrayList<>();
        for(RawSegment candidate:input){
            Vec3d mid=midpoint(candidate); List<RawSegment> best=null; double bestDistance=Double.POSITIVE_INFINITY;
            for(List<RawSegment> cluster:clusters){
                Vec3d center=Vec3d.ZERO; for(RawSegment value:cluster)center=center.add(midpoint(value));
                center=center.multiply(1.0/cluster.size()); double d=center.distanceTo(mid);
                if(d<=radius && d<bestDistance){bestDistance=d;best=cluster;}
            }
            if(best==null){best=new ArrayList<>();clusters.add(best);} best.add(candidate);
        }
        return clusters;
    }

    /**
     * Key only uses the longitudinal sample itself.  Cross-section tile offsets are perpendicular to
     * the tangent, so dot(c,tangent) is identical for low/middle/high and left/middle/right copies.
     * A quantised tangent keeps adjacent curved samples separate while merging only true duplicates.
     */
    private static SegmentKey segmentKey(RawSegment segment) {
        Vec3d delta = segment.c1.subtract(segment.c0);
        double length = delta.length();
        Vec3d tangent = length < EPS ? new Vec3d(1,0,0) : delta.multiply(1.0 / length);
        if (canonicalReverse(tangent)) tangent = tangent.multiply(-1.0);
        double q0 = segment.c0.dotProduct(tangent);
        double q1 = segment.c1.dotProduct(tangent);
        if (q1 < q0) { double swap=q0; q0=q1; q1=swap; }
        return new SegmentKey((int)Math.round(tangent.x * 128.0),
                (int)Math.round(tangent.y * 128.0),
                (int)Math.round(tangent.z * 128.0),
                Math.round(q0 / 0.0625), Math.round(q1 / 0.0625));
    }

    private static boolean canonicalReverse(Vec3d tangent) {
        if (Math.abs(tangent.x) > 1.0E-6) return tangent.x < 0.0;
        if (Math.abs(tangent.y) > 1.0E-6) return tangent.y < 0.0;
        return tangent.z < 0.0;
    }

    private static RawSegment mergeGroup(List<RawSegment> group,
                                         double exactWidthSpan, double exactRadialSpan) {
        if (group.size() == 1) return group.get(0);

        Vec3d c0Guess = Vec3d.ZERO;
        Vec3d c1Guess = Vec3d.ZERO;
        Vec3d w0 = null, w1 = null, r0 = null, r1 = null;
        for (RawSegment segment : group) {
            c0Guess = c0Guess.add(segment.c0);
            c1Guess = c1Guess.add(segment.c1);
            w0 = alignedAxis(w0, segment.width0);
            w1 = alignedAxis(w1, segment.width1);
            r0 = alignedAxis(r0, segment.radial0);
            r1 = alignedAxis(r1, segment.radial1);
        }
        c0Guess = c0Guess.multiply(1.0 / group.size());
        c1Guess = c1Guess.multiply(1.0 / group.size());
        w0 = normalizedOr(w0, new Vec3d(0,0,1));
        w1 = normalizedOr(w1, w0);
        r0 = orthogonalized(normalizedOr(r0, new Vec3d(0,1,0)), w0);
        r1 = orthogonalized(normalizedOr(r1, r0), w1);

        CrossSection merged0 = mergeCrossSection(group, false, c0Guess, w0, r0);
        CrossSection merged1 = mergeCrossSection(group, true, c1Guess, w1, r1);
        Vec3d mergedMid = merged0.center.add(merged1.center).multiply(0.5);
        RawSegment ownerSegment = group.get(0);
        double best = midpoint(ownerSegment).squaredDistanceTo(mergedMid);
        for (int i=1;i<group.size();i++) {
            double distance = midpoint(group.get(i)).squaredDistanceTo(mergedMid);
            if (distance < best) { best = distance; ownerSegment = group.get(i); }
        }
        double width0 = finiteSpan(exactWidthSpan, merged0.widthSpan);
        double width1 = finiteSpan(exactWidthSpan, merged1.widthSpan);
        double radial0 = finiteSpan(exactRadialSpan, merged0.radialSpan);
        double radial1 = finiteSpan(exactRadialSpan, merged1.radialSpan);
        return new RawSegment(ownerSegment.owner,
                merged0.center, merged1.center,
                w0, w1, r0, r1,
                width0, width1, radial0, radial1);
    }

    private static CrossSection mergeCrossSection(List<RawSegment> group, boolean end,
                                                  Vec3d guess, Vec3d width, Vec3d radial) {
        double minW=Double.POSITIVE_INFINITY,maxW=Double.NEGATIVE_INFINITY;
        double minR=Double.POSITIVE_INFINITY,maxR=Double.NEGATIVE_INFINITY;
        for (RawSegment segment : group) {
            Vec3d center = end ? segment.c1 : segment.c0;
            Vec3d sw = end ? segment.width1 : segment.width0;
            Vec3d sr = end ? segment.radial1 : segment.radial0;
            double ws = end ? segment.widthSpan1 : segment.widthSpan0;
            double rs = end ? segment.radialSpan1 : segment.radialSpan0;
            Vec3d offset = center.subtract(guess);
            double wc = offset.dotProduct(width);
            double rc = offset.dotProduct(radial);
            // Project the complete local half-rectangle into the merged basis instead of assuming
            // source axes are perfectly parallel after clipping/numerical rounding.
            double halfW = Math.abs(sw.dotProduct(width))*ws*0.5
                    + Math.abs(sr.dotProduct(width))*rs*0.5;
            double halfR = Math.abs(sw.dotProduct(radial))*ws*0.5
                    + Math.abs(sr.dotProduct(radial))*rs*0.5;
            minW=Math.min(minW,wc-halfW); maxW=Math.max(maxW,wc+halfW);
            minR=Math.min(minR,rc-halfR); maxR=Math.max(maxR,rc+halfR);
        }
        if (!Double.isFinite(minW) || maxW-minW < 1.0E-5) { minW=-0.5; maxW=0.5; }
        if (!Double.isFinite(minR) || maxR-minR < 1.0E-5) { minR=-0.5; maxR=0.5; }
        double midW=(minW+maxW)*0.5, midR=(minR+maxR)*0.5;
        Vec3d center=guess.add(width.multiply(midW)).add(radial.multiply(midR));
        return new CrossSection(center,maxW-minW,maxR-minR);
    }

    private static Vec3d alignedAxis(Vec3d accumulated, Vec3d value) {
        if (value == null || value.lengthSquared() < EPS) return accumulated;
        Vec3d v=value.normalize();
        if (accumulated == null || accumulated.lengthSquared() < EPS) return v;
        if (accumulated.dotProduct(v) < 0.0) v=v.multiply(-1.0);
        return accumulated.add(v);
    }

    private static Vec3d normalizedOr(Vec3d value, Vec3d fallback) {
        return value == null || value.lengthSquared() < EPS ? fallback.normalize() : value.normalize();
    }

    private static Vec3d orthogonalized(Vec3d value, Vec3d width) {
        Vec3d result=value.subtract(width.multiply(value.dotProduct(width)));
        if (result.lengthSquared() < EPS) {
            Vec3d candidate=Math.abs(width.y)<0.8?new Vec3d(0,1,0):new Vec3d(1,0,0);
            result=candidate.subtract(width.multiply(candidate.dotProduct(width)));
        }
        return result.normalize();
    }

    private record CrossSection(Vec3d center,double widthSpan,double radialSpan) {}
    private record SegmentKey(int tx,int ty,int tz,long q0,long q1) {}

    private static double finiteSpan(double preferred, double fallback) {
        return Double.isFinite(preferred) && preferred >= 1.0 ? preferred : Math.max(1.0, fallback);
    }

    private static double firstFiniteGroupWidth(List<ArcRibbonBlockEntity> members) {
        for (ArcRibbonBlockEntity member : members) {
            double value=ArcPrismTags.groupWidthSpan(member);
            if (Double.isFinite(value)) return value;
        }
        return Double.NaN;
    }

    private static double firstFiniteGroupRadial(List<ArcRibbonBlockEntity> members) {
        for (ArcRibbonBlockEntity member : members) {
            double value=ArcPrismTags.groupRadialSpan(member);
            if (Double.isFinite(value)) return value;
        }
        return Double.NaN;
    }

    private static Direction firstGroupInnerFace(List<ArcRibbonBlockEntity> members) {
        for (ArcRibbonBlockEntity member : members) {
            Direction value=ArcPrismTags.groupInnerFace(member);
            if (value != null) return value;
        }
        return null;
    }

    private static List<Segment> order(List<RawSegment> raw) {
        if (raw.isEmpty()) return List.of();
        List<SegmentChainOrder.Edge<RawSegment>> edges = new ArrayList<>(raw.size());
        for (RawSegment segment : raw) {
            edges.add(new SegmentChainOrder.Edge<>(chainPoint(segment.c0), chainPoint(segment.c1), segment));
        }
        List<SegmentChainOrder.Oriented<RawSegment>> chain = SegmentChainOrder.order(edges, TOPOLOGY_ENDPOINT_EPS);
        if (chain.isEmpty()) return List.of();
        // Never return a visually plausible *partial* component.  A partial ordered chain is the
        // exact failure mode that used to make long straight/outer sections disappear.  Callers can
        // fall back to raw-prism rendering instead of silently dropping unmatched segments.
        if (chain.size() != raw.size()) return List.of();

        List<Segment> out = new ArrayList<>(chain.size());
        Vec3d prevW = null;
        Vec3d prevR = null;
        double cumulative = 0.0;
        for (SegmentChainOrder.Oriented<RawSegment> ordered : chain) {
            RawSegment r = ordered.reversed() ? ordered.value().reversed() : ordered.value();
            Vec3d w0 = r.width0;
            Vec3d w1 = r.width1;
            Vec3d r0 = r.radial0;
            Vec3d r1 = r.radial1;
            if (prevW != null && prevW.dotProduct(w0) < 0.0) {
                w0 = w0.multiply(-1.0);
                w1 = w1.multiply(-1.0);
            }
            if (prevR != null && prevR.dotProduct(r0) < 0.0) {
                r0 = r0.multiply(-1.0);
                r1 = r1.multiply(-1.0);
            }
            // Use the clustered topology node itself as the station coordinate.  Two generated
            // prism endpoints may differ by a few hundredths because one belongs to a straight
            // sample and the other to the circular sample.  Keeping the raw values creates a real
            // render gap later; the graph node is the shared geometric joint by definition.
            Vec3d c0 = new Vec3d(ordered.start().x(), ordered.start().y(), ordered.start().z());
            Vec3d c1 = new Vec3d(ordered.end().x(), ordered.end().y(), ordered.end().z());
            double length = c0.distanceTo(c1);
            if (length < 1.0E-6) continue;
            out.add(new Segment(r.owner, c0, c1, w0, w1, r0, r1,
                    r.widthSpan0, r.widthSpan1, r.radialSpan0, r.radialSpan1,
                    cumulative, length));
            cumulative += length;
            prevW = w1;
            prevR = r1;
        }
        return List.copyOf(out);
    }

    private static Component orientToStoredEndpointDirection(Component component) {
        if (component == null || component.segments.isEmpty() || component.startModelBlock == null) return component;
        BlockEntity be = component.world.getBlockEntity(component.startModelBlock);
        if (!(be instanceof ModelBlockEntity model)) return component;
        Direction direction = model.getArcDirection();
        if (direction == null) return component;
        Vec3d first = component.segments.get(0).c1.subtract(component.segments.get(0).c0);
        if (first.lengthSquared() < EPS) return component;
        Vec3d towardArc = new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        return towardArc.dotProduct(first) < 0.0 ? reverse(component) : component;
    }

    private static Component orientToPreferredEndpoint(Component component, BlockPos endpoint) {
        if (component == null || component.segments.isEmpty()) return component;
        if (endpoint.equals(component.startModelBlock)) return component;
        if (endpoint.equals(component.endModelBlock)) return reverse(component);
        Vec3d center = Vec3d.ofCenter(endpoint);
        Segment first = component.segments.get(0);
        Segment last = component.segments.get(component.segments.size() - 1);
        double startDistance = center.squaredDistanceTo(first.c0);
        double endDistance = center.squaredDistanceTo(last.c1);
        return endDistance + 1.0E-6 < startDistance ? reverse(component) : component;
    }

    private static Component reverse(Component component) {
        if (component == null || component.segments.isEmpty()) return component;
        List<Segment> reversed = new ArrayList<>(component.segments.size());
        Vec3d prevW = null;
        Vec3d prevR = null;
        double cumulative = 0.0;
        for (int index = component.segments.size() - 1; index >= 0; index--) {
            Segment source = component.segments.get(index);
            Vec3d w0 = source.width1;
            Vec3d w1 = source.width0;
            Vec3d r0 = source.radial1;
            Vec3d r1 = source.radial0;
            if (prevW != null && prevW.dotProduct(w0) < 0.0) {
                w0 = w0.multiply(-1.0);
                w1 = w1.multiply(-1.0);
            }
            if (prevR != null && prevR.dotProduct(r0) < 0.0) {
                r0 = r0.multiply(-1.0);
                r1 = r1.multiply(-1.0);
            }
            reversed.add(new Segment(source.owner, source.c1, source.c0, w0, w1, r0, r1,
                    source.widthSpan1, source.widthSpan0, source.radialSpan1, source.radialSpan0,
                    cumulative, source.length));
            cumulative += source.length;
            prevW = w1;
            prevR = r1;
        }
        ArcRibbonBlockEntity leader = reversed.get(0).owner;
        return new Component(component.world, component.members, List.copyOf(reversed), leader,
                component.endModelBlock, component.startModelBlock,
                component.groupId, component.exactWidthSpan, component.exactRadialSpan, component.innerFace);
    }

    private static SegmentChainOrder.Point chainPoint(Vec3d point) {
        return new SegmentChainOrder.Point(point.x, point.y, point.z);
    }

    private static BlockPos nearestModelBlock(World world, Vec3d point) {
        BlockPos base = BlockPos.ofFloored(point);
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -ENDPOINT_SEARCH_RADIUS_XY; dx <= ENDPOINT_SEARCH_RADIUS_XY; dx++) {
            for (int dy = -ENDPOINT_SEARCH_RADIUS_XY; dy <= ENDPOINT_SEARCH_RADIUS_XY; dy++) {
                for (int dz = -ENDPOINT_SEARCH_RADIUS_Z; dz <= ENDPOINT_SEARCH_RADIUS_Z; dz++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    if (!ModelSystemMod.isModelHolder(world.getBlockState(pos))) continue;
                    double d = Vec3d.ofCenter(pos).squaredDistanceTo(point);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static Vec3d averageTangent(List<RawSegment> segments) {
        Vec3d tangent = Vec3d.ZERO;
        for (RawSegment segment : segments) {
            Vec3d delta = segment.c1.subtract(segment.c0);
            double length = delta.length();
            if (length > EPS) tangent = tangent.add(delta.multiply(1.0 / length));
        }
        if (tangent.lengthSquared() < EPS && !segments.isEmpty()) {
            tangent = segments.get(0).c1.subtract(segments.get(0).c0);
        }
        return tangent.lengthSquared() < EPS ? Vec3d.ZERO : tangent.normalize();
    }

    private static long representativeKey(RawSegment segment, Vec3d tangent) {
        long start = Math.round(segment.c0.dotProduct(tangent) / REPRESENTATIVE_BIN);
        long end = Math.round(segment.c1.dotProduct(tangent) / REPRESENTATIVE_BIN);
        return (start << 32) ^ (end & 0xffffffffL);
    }

    private static Vec3d midpoint(RawSegment segment) {
        return segment.c0.add(segment.c1).multiply(0.5);
    }

    private static double alignment(RawSegment segment, Vec3d tangent) {
        Vec3d delta = segment.c1.subtract(segment.c0);
        double length = delta.length();
        return length < EPS ? -1.0 : Math.abs(delta.multiply(1.0 / length).dotProduct(tangent));
    }

    private static Vec3d average(BlockPos holder, float[] vertices, int from, int to) {
        double x = 0.0, y = 0.0, z = 0.0;
        for (int index = from; index < to; index++) {
            x += vertices[index * 3];
            y += vertices[index * 3 + 1];
            z += vertices[index * 3 + 2];
        }
        double count = to - from;
        return new Vec3d(holder.getX() + x / count, holder.getY() + y / count, holder.getZ() + z / count);
    }

    private static Vec3d edge(float[] vertices, int a, int b) {
        return new Vec3d(vertices[b * 3] - vertices[a * 3],
                vertices[b * 3 + 1] - vertices[a * 3 + 1],
                vertices[b * 3 + 2] - vertices[a * 3 + 2]);
    }

    private record RawSegment(ArcRibbonBlockEntity owner, Vec3d c0, Vec3d c1,
                              Vec3d width0, Vec3d width1,
                              Vec3d radial0, Vec3d radial1,
                              double widthSpan0, double widthSpan1,
                              double radialSpan0, double radialSpan1) {
        RawSegment reversed() {
            return new RawSegment(owner, c1, c0, width1, width0, radial1, radial0,
                    widthSpan1, widthSpan0, radialSpan1, radialSpan0);
        }
    }

    public record Segment(ArcRibbonBlockEntity owner, Vec3d c0, Vec3d c1,
                          Vec3d width0, Vec3d width1,
                          Vec3d radial0, Vec3d radial1,
                          double widthSpan0, double widthSpan1,
                          double radialSpan0, double radialSpan1,
                          double s0, double length) {}

    public record Component(World world, List<ArcRibbonBlockEntity> members,
                            List<Segment> segments,
                            ArcRibbonBlockEntity leader,
                            BlockPos startModelBlock,
                            BlockPos endModelBlock,
                            long groupId,
                            double exactWidthSpan,
                            double exactRadialSpan,
                            Direction innerFace) {
        public double totalLength() {
            Segment last = segments.get(segments.size() - 1);
            return last.s0 + last.length;
        }
    }
}
