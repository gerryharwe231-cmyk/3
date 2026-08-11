package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Stamps endpoint role and the actual generated cross-section on the two ModelBlock endpoints. */
public final class ArcEndpointMetadataBinder {
    private ArcEndpointMetadataBinder() {}

    public static void bind(World world, BlockPos start, Direction requestedInnerFace) {
        ArcComponentFinder.Component component = ArcComponentFinder.fromClickedModelBlock(world, start);
        if (component == null || component.segments().isEmpty()) return;
        Direction storedInner = ArcPrismTags.groupInnerFace(component.leader());
        Direction innerFace = storedInner == null
                ? (requestedInnerFace == null ? Direction.UP : requestedInnerFace) : storedInner;

        ModelBlockEntity startModel = component.startModelBlock() == null ? null
                : ModelEndpointService.ensureEndpoint(world, component.startModelBlock(), innerFace);
        ModelBlockEntity endModel = component.endModelBlock() == null ? null
                : ModelEndpointService.ensureEndpoint(world, component.endModelBlock(), innerFace);

        List<ArcStationFrames.Station> stations = ArcStationFrames.build(component);
        if (stations.size() != component.segments().size() + 1) return;
        ArcStationFrames.Station middleStation = stations.get(stations.size() / 2);
        ArcCrossSectionMapping.Mapping groupMapping = ArcCrossSectionMapping.resolve(
                middleStation.width(), middleStation.radial(),
                middleStation.widthSpan(), middleStation.radialSpan(), innerFace);
        long groupId = ArcPrismTags.groupId(component.leader());
        stampRenderLeader(world, component);

        if (startModel != null) {
            Vec3d towardArc = component.segments().get(0).c1().subtract(component.segments().get(0).c0());
            startModel.setArcGroupId(groupId);
            startModel.setTerminalEnd(false);
            startModel.setArcMetadata(dominantDirection(towardArc), innerFace);
            startModel.setEndpointFrame(ArcStationFrames.alignEndpoint(stations.get(0), startModel), groupMapping);
        }
        if (endModel != null) {
            ArcComponentFinder.Segment last = component.segments().get(component.segments().size()-1);
            Vec3d towardArc = last.c0().subtract(last.c1());
            endModel.setArcGroupId(groupId);
            endModel.setTerminalEnd(true);
            endModel.setArcMetadata(dominantDirection(towardArc), innerFace);
            endModel.setEndpointFrame(ArcStationFrames.alignEndpoint(stations.get(stations.size()-1), endModel), groupMapping);
        }
    }

    /** Select one central ArcRibbon BE to render the entire captured-model group. */
    private static void stampRenderLeader(World world, ArcComponentFinder.Component component) {
        if (component.members().isEmpty()) return;
        Vec3d centroid = Vec3d.ZERO;
        for (ArcRibbonBlockEntity member : component.members()) centroid = centroid.add(Vec3d.ofCenter(member.getPos()));
        centroid = centroid.multiply(1.0 / component.members().size());
        ArcRibbonBlockEntity leader = component.members().get(0);
        double best = Vec3d.ofCenter(leader.getPos()).squaredDistanceTo(centroid);
        for (ArcRibbonBlockEntity candidate : component.members()) {
            double distance = Vec3d.ofCenter(candidate.getPos()).squaredDistanceTo(centroid);
            if (distance < best) { best = distance; leader = candidate; }
        }
        double radius = 0.0;
        Vec3d leaderCenter = Vec3d.ofCenter(leader.getPos());
        for (ArcRibbonBlockEntity member : component.members()) {
            radius = Math.max(radius, leaderCenter.distanceTo(Vec3d.ofCenter(member.getPos())));
        }
        radius += Math.max(component.exactWidthSpan(), component.exactRadialSpan()) * 0.5 + 32.0;

        for (ArcRibbonBlockEntity member : component.members()) {
            List<ArcRibbonBlockEntity.Prism> prisms = new ArrayList<>();
            for (ArcRibbonBlockEntity.Prism prism : member.getPrisms()) {
                if (!ArcPrismTags.isRenderLeader(prism)) prisms.add(prism);
            }
            if (member == leader) prisms.add(ArcPrismTags.renderLeader(radius));
            member.setData(member.getSourceState(), prisms, new ArrayList<>(member.getSurfaces()));
            world.updateListeners(member.getPos(), world.getBlockState(member.getPos()), world.getBlockState(member.getPos()), 2);
        }
    }

    private static Direction dominantDirection(Vec3d vector) {
        if (vector == null || vector.lengthSquared() < 1.0E-10) return Direction.NORTH;
        double ax=Math.abs(vector.x), ay=Math.abs(vector.y), az=Math.abs(vector.z);
        if (ay>=ax && ay>=az) return vector.y>=0?Direction.UP:Direction.DOWN;
        if (ax>=az) return vector.x>=0?Direction.EAST:Direction.WEST;
        return vector.z>=0?Direction.SOUTH:Direction.NORTH;
    }
}
