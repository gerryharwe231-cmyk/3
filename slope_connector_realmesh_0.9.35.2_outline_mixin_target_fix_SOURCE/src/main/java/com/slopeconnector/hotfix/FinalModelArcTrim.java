package com.slopeconnector.hotfix;

import com.slopeconnector.model.ArcComponentFinder;
import com.slopeconnector.model.ArcPrismTags;
import com.slopeconnector.model.ArcStationFrames;
import com.slopeconnector.model.ModelBlockEntity;
import com.slopeconnector.model.ModelSystemMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-runs auto trim after the final model-arc width/thickness geometry exists.
 *
 * <p>The embedded 0.9.17 auto-trimmer runs inside ArcRibbonGenerator before our dimension mixin has
 * expanded the prisms.  That means a correct base-width cut is immediately invalid after widening
 * or thickening.  It also intentionally accepts only ordinary full cubes, so stairs/slabs are never
 * cut.  This final pass uses the actual final generated prism geometry, supports any non-BE block
 * whose outline/collision shape can be expressed as boxes, and stores ONLY the geometrically
 * remaining source volume in the trim holder.  Arc collision remains owned by the arc system rather
 * than being merged into the cut source block.</p>
 */
public final class FinalModelArcTrim {
    private static final double INTERSECTION_EPS = 2.0E-5;
    private static final double BOUNDS_EPS = 1.0E-6;
    private static final int VOXEL_RESOLUTION = 8;
    private static final int MAX_CELLS = 24000;

    private FinalModelArcTrim() {}

    public static int rebuild(World world, BlockPos startModelBlock) {
        if (world == null || startModelBlock == null || !ArcAutoTrimSettings.enabled()) return 0;
        ArcComponentFinder.Component component = ArcComponentFinder.fromClickedModelBlock(world, startModelBlock);
        if (component == null || component.segments().isEmpty()) return 0;

        // Auto-trim must follow the FINAL generated 3D geometry exactly.  Reconstructing cutters
        // from centreline stations is good for rendering continuity, but a rising Z/elevation arc can
        // rotate its cross-section between samples; a reconstructed station can then miss the actual
        // upper/lower/left/right prism corner.  Index every final non-metadata prism directly instead.
        Map<BlockPos, List<ArcAutoTrim.WorldPrism>> cuttersByCell = indexFinalPrismCutters(component);
        // The visible endpoint is not just the centre ModelEndpointBlock.  Width/thickness > 1
        // repeats one-block endpoint tiles to both lateral/vertical sides.  Those tiles previously
        // never entered final auto-trim, so neighbouring ordinary blocks survived inside the visible
        // endpoint and their collision overlapped it.  Index the exact endpoint tile volumes too.
        appendEndpointCutters(world, component.startModelBlock(), cuttersByCell);
        appendEndpointCutters(world, component.endModelBlock(), cuttersByCell);
        if (cuttersByCell.isEmpty()) return 0;

        Set<BlockPos> excluded = new HashSet<>();
        for (ArcRibbonBlockEntity member : component.members()) excluded.add(member.getPos());
        if (component.startModelBlock() != null) excluded.add(component.startModelBlock());
        if (component.endModelBlock() != null) excluded.add(component.endModelBlock());

        int changed = 0;
        int visited = 0;
        for (Map.Entry<BlockPos, List<ArcAutoTrim.WorldPrism>> entry : cuttersByCell.entrySet()) {
            if (++visited > MAX_CELLS) break;
            BlockPos pos = entry.getKey();
            if (excluded.contains(pos)) continue;

            BlockState current = world.getBlockState(pos);
            BlockState source = sourceState(world, pos, current);
            if (!isTrimmable(source)) continue;

            List<ConvexGeometry.Poly> sourcePolys = sourcePolys(world, pos, source);
            if (sourcePolys.isEmpty()) continue;

            CutResult result = subtract(sourcePolys, entry.getValue());
            if (!result.touched) continue;

            double remainingVolume = volume(result.remaining);
            if (remainingVolume <= INTERSECTION_EPS || result.remaining.isEmpty()) {
                // When the source shape is completely consumed, leave the cell empty.  The collision
                // proxy builder that runs immediately after this pass will place arc collision here.
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                changed++;
                continue;
            }

            List<ArcTrimBlockEntity.Triangle> triangles = visibleRemainderTriangles(
                    result.remaining, pos, result.cutters);
            if (triangles.isEmpty()) continue;

            // IMPORTANT: ARC_TRIM represents the cut ordinary block, not the arc itself.  Merging
            // arc volume into these boxes made a player hit/break an invisible collision composite
            // while the visible arc remained elsewhere.  The trim holder owns only the remaining
            // source volume; the arc/collision-proxy system remains independently breakable.
            List<ConvexGeometry.Poly> collisionPolys = new ArrayList<>();
            Vec3d toLocal = new Vec3d(-pos.getX(), -pos.getY(), -pos.getZ());
            for (ConvexGeometry.Poly poly : result.remaining) {
                collisionPolys.add(ConvexGeometry.translated(poly, toLocal));
            }

            List<ArcTrimBlockEntity.TrimBox> boxes = new ArrayList<>();
            for (VoxelShapeUtil.BoxSpec box : VoxelShapeUtil.voxelizePolys(collisionPolys, VOXEL_RESOLUTION)) {
                boxes.add(new ArcTrimBlockEntity.TrimBox(
                        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
            }

            if (current.getBlock() != ArcHotfixMod.ARC_TRIM) {
                world.setBlockState(pos, ArcHotfixMod.ARC_TRIM.getDefaultState(), 2);
            }
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof ArcTrimBlockEntity trim) {
                trim.setData(source, triangles, boxes);
                BlockState holder = world.getBlockState(pos);
                world.updateListeners(pos, holder, holder, 2);
                changed++;
            } else {
                // Failsafe: never destroy the original source block if the trim BE could not exist.
                world.setBlockState(pos, source, 2);
            }
        }
        return changed;
    }

    private static BlockState sourceState(World world, BlockPos pos, BlockState current) {
        if (current.getBlock() == ArcHotfixMod.ARC_TRIM
                && world.getBlockEntity(pos) instanceof ArcTrimBlockEntity trim) {
            return trim.getSourceState();
        }
        return current;
    }

    private static boolean isTrimmable(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.getBlock() == ArcHotfixMod.ARC_RIBBON || state.getBlock() == ArcHotfixMod.ARC_TRIM) return false;
        if (ModelSystemMod.isModelHolder(state)) return false;
        if (!state.getFluidState().isEmpty()) return false;
        // Block entities can have independent render/state data that ArcTrimBlockEntity cannot carry.
        return !state.hasBlockEntity();
    }

    private static List<ConvexGeometry.Poly> sourcePolys(World world, BlockPos pos, BlockState state) {
        VoxelShape shape;
        try {
            shape = state.getOutlineShape(world, pos, ShapeContext.absent());
            if (shape.isEmpty()) shape = state.getCollisionShape(world, pos, ShapeContext.absent());
        } catch (RuntimeException error) {
            return List.of();
        }
        if (shape.isEmpty()) return List.of();
        List<ConvexGeometry.Poly> result = new ArrayList<>();
        for (Box box : shape.getBoundingBoxes()) {
            if (box.maxX - box.minX <= BOUNDS_EPS || box.maxY - box.minY <= BOUNDS_EPS || box.maxZ - box.minZ <= BOUNDS_EPS) continue;
            float[] xyz = worldBox(pos, box);
            ConvexGeometry.Poly poly = ConvexGeometry.prism(xyz);
            if (poly != null && ConvexGeometry.volume(poly) > INTERSECTION_EPS) result.add(poly);
        }
        return result;
    }

    /**
     * Triangulates the real post-subtraction boundary, not merely the six cube boundary planes.
     * This is required for slabs/stairs and for diagonal Z/elevation cuts.
     */
    private static List<ArcTrimBlockEntity.Triangle> visibleRemainderTriangles(
            List<ConvexGeometry.Poly> remaining, BlockPos pos, List<ConvexGeometry.Poly> cutters) {
        List<ConvexGeometry.Plane> cutPlanes = new ArrayList<>();
        for (ConvexGeometry.Poly cutter : cutters) cutPlanes.addAll(ConvexGeometry.planes(cutter));

        Map<String, ArcTrimBlockEntity.Triangle> visible = new java.util.LinkedHashMap<>();
        Set<String> canceled = new HashSet<>();
        for (float[] xyz : ConvexGeometry.triangulate(remaining, pos)) {
            if (xyz == null || xyz.length != 9 || triangleAreaSquared(xyz) < 1.0E-12) continue;
            Vec3d centroid = new Vec3d(
                    pos.getX() + (xyz[0] + xyz[3] + xyz[6]) / 3.0,
                    pos.getY() + (xyz[1] + xyz[4] + xyz[7]) / 3.0,
                    pos.getZ() + (xyz[2] + xyz[5] + xyz[8]) / 3.0);
            int owners = 0;
            for (ConvexGeometry.Poly poly : remaining) {
                if (ConvexGeometry.contains(poly, centroid)) owners++;
                if (owners > 1) break;
            }
            // A face shared by two subtraction pieces is an internal partition, not visible geometry.
            if (owners > 1) continue;

            String key = triangleKey(xyz);
            if (canceled.contains(key)) continue;
            if (visible.containsKey(key)) {
                visible.remove(key);
                canceled.add(key);
                continue;
            }
            boolean cutFace = liesOnCutPlane(xyz, pos, cutPlanes);
            visible.put(key, new ArcTrimBlockEntity.Triangle(xyz.clone(), cutFace));
        }
        return List.copyOf(visible.values());
    }

    private static boolean liesOnCutPlane(float[] xyz, BlockPos pos,
                                          List<ConvexGeometry.Plane> planes) {
        for (ConvexGeometry.Plane plane : planes) {
            boolean all = true;
            for (int vertex = 0; vertex < 3; vertex++) {
                Vec3d point = new Vec3d(pos.getX() + xyz[vertex * 3],
                        pos.getY() + xyz[vertex * 3 + 1], pos.getZ() + xyz[vertex * 3 + 2]);
                if (Math.abs(plane.side(point)) > 2.5E-4) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static double triangleAreaSquared(float[] xyz) {
        Vec3d a = new Vec3d(xyz[0], xyz[1], xyz[2]);
        Vec3d b = new Vec3d(xyz[3], xyz[4], xyz[5]);
        Vec3d c = new Vec3d(xyz[6], xyz[7], xyz[8]);
        return b.subtract(a).crossProduct(c.subtract(a)).lengthSquared() * 0.25;
    }

    private static String triangleKey(float[] xyz) {
        String[] vertices = new String[3];
        for (int i = 0; i < 3; i++) {
            long x = Math.round(xyz[i * 3] * 100000.0);
            long y = Math.round(xyz[i * 3 + 1] * 100000.0);
            long z = Math.round(xyz[i * 3 + 2] * 100000.0);
            vertices[i] = x + ":" + y + ":" + z;
        }
        java.util.Arrays.sort(vertices);
        return vertices[0] + "|" + vertices[1] + "|" + vertices[2];
    }

    private static CutResult subtract(List<ConvexGeometry.Poly> source,
                                      List<ArcAutoTrim.WorldPrism> cutters) {
        List<ConvexGeometry.Poly> remaining = new ArrayList<>(source);
        List<ConvexGeometry.Poly> used = new ArrayList<>();
        boolean touched = false;
        for (ArcAutoTrim.WorldPrism cutter : cutters) {
            List<ConvexGeometry.Poly> next = new ArrayList<>();
            boolean usedThis = false;
            for (ConvexGeometry.Poly poly : remaining) {
                if (!intersects(ConvexGeometry.bounds(poly), cutter.bounds)) {
                    next.add(poly);
                    continue;
                }
                ConvexGeometry.Poly intersection = ConvexGeometry.intersection(poly, cutter.poly);
                if (intersection == null || ConvexGeometry.volume(intersection) <= INTERSECTION_EPS) {
                    next.add(poly);
                    continue;
                }
                touched = true;
                usedThis = true;
                next.addAll(ConvexGeometry.subtract(List.of(poly), cutter.poly));
            }
            if (usedThis) used.add(cutter.poly);
            remaining = next;
            if (remaining.isEmpty()) break;
        }
        return new CutResult(List.copyOf(remaining), List.copyOf(used), touched);
    }

    private static void appendEndpointCutters(World world, BlockPos endpoint,
                                              Map<BlockPos, List<ArcAutoTrim.WorldPrism>> result) {
        if (endpoint == null || !(world.getBlockEntity(endpoint) instanceof ModelBlockEntity model)) return;
        Vec3d width = model.getEndpointLateralAxis();
        Vec3d radial = model.getEndpointVerticalAxis();
        if (width.lengthSquared() < 1.0E-10) width = new Vec3d(0,0,1); else width = width.normalize();
        if (radial.lengthSquared() < 1.0E-10) radial = new Vec3d(0,1,0); else radial = radial.normalize();
        Direction longitudinalDirection = model.getArcDirection() == null ? Direction.EAST
                : (model.isTerminalEnd() ? model.getArcDirection().getOpposite() : model.getArcDirection());
        Vec3d tangent = new Vec3d(longitudinalDirection.getOffsetX(), longitudinalDirection.getOffsetY(), longitudinalDirection.getOffsetZ());
        if (tangent.lengthSquared() < 1.0E-10) tangent = width.crossProduct(radial); else tangent = tangent.normalize();

        double widthSpan = Math.max(1.0, model.getEndpointLateralSpan());
        double radialSpan = Math.max(1.0, model.getEndpointVerticalSpan());
        int widthTiles = Math.max(1, Math.min(32, (int)Math.round(widthSpan)));
        int radialTiles = Math.max(1, Math.min(32, (int)Math.round(radialSpan)));
        double widthCell = widthSpan / widthTiles;
        double radialCell = radialSpan / radialTiles;
        Vec3d base = Vec3d.ofCenter(endpoint);
        Vec3d halfT = tangent.multiply(0.5);

        for (int wi=0; wi<widthTiles; wi++) {
            for (int ri=0; ri<radialTiles; ri++) {
                double wo=((wi+0.5)-widthTiles*0.5)*widthCell;
                double ro=((ri+0.5)-radialTiles*0.5)*radialCell;
                Vec3d center=base.add(width.multiply(wo)).add(radial.multiply(ro));
                Vec3d halfW=width.multiply(widthCell*0.5);
                Vec3d halfR=radial.multiply(radialCell*0.5);
                float[] xyz=new float[24];
                put(xyz,0,center.subtract(halfT).subtract(halfW).subtract(halfR));
                put(xyz,1,center.subtract(halfT).add(halfW).subtract(halfR));
                put(xyz,2,center.subtract(halfT).add(halfW).add(halfR));
                put(xyz,3,center.subtract(halfT).subtract(halfW).add(halfR));
                put(xyz,4,center.add(halfT).subtract(halfW).subtract(halfR));
                put(xyz,5,center.add(halfT).add(halfW).subtract(halfR));
                put(xyz,6,center.add(halfT).add(halfW).add(halfR));
                put(xyz,7,center.add(halfT).subtract(halfW).add(halfR));
                indexCutter(result,new ArcAutoTrim.WorldPrism(xyz));
            }
        }
    }

    private static void indexCutter(Map<BlockPos, List<ArcAutoTrim.WorldPrism>> result,
                                    ArcAutoTrim.WorldPrism cutter) {
        Box bounds=cutter.bounds;
        int minX=(int)Math.floor(bounds.minX + BOUNDS_EPS);
        int minY=(int)Math.floor(bounds.minY + BOUNDS_EPS);
        int minZ=(int)Math.floor(bounds.minZ + BOUNDS_EPS);
        int maxX=(int)Math.floor(bounds.maxX - BOUNDS_EPS);
        int maxY=(int)Math.floor(bounds.maxY - BOUNDS_EPS);
        int maxZ=(int)Math.floor(bounds.maxZ - BOUNDS_EPS);
        for(int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++) for(int z=minZ;z<=maxZ;z++) {
            result.computeIfAbsent(new BlockPos(x,y,z),ignored->new ArrayList<>()).add(cutter);
        }
    }

    private static Map<BlockPos, List<ArcAutoTrim.WorldPrism>> indexFinalPrismCutters(
            ArcComponentFinder.Component component) {
        Map<BlockPos, List<ArcAutoTrim.WorldPrism>> result = new HashMap<>();
        int indexedCells = 0;
        for (ArcRibbonBlockEntity member : component.members()) {
            BlockPos holder = member.getPos();
            for (ArcRibbonBlockEntity.Prism prism : member.getPrisms()) {
                if (ArcPrismTags.isMetadata(prism)) continue;
                float[] local = prism.xyz();
                if (local == null || local.length != 24) continue;
                float[] world = new float[24];
                for (int vertex = 0; vertex < 8; vertex++) {
                    world[vertex * 3] = local[vertex * 3] + holder.getX();
                    world[vertex * 3 + 1] = local[vertex * 3 + 1] + holder.getY();
                    world[vertex * 3 + 2] = local[vertex * 3 + 2] + holder.getZ();
                }
                ArcAutoTrim.WorldPrism cutter = new ArcAutoTrim.WorldPrism(world);
                indexCutter(result, cutter);
                Box bounds = cutter.bounds;
                int minX=(int)Math.floor(bounds.minX + BOUNDS_EPS), maxX=(int)Math.floor(bounds.maxX - BOUNDS_EPS);
                int minY=(int)Math.floor(bounds.minY + BOUNDS_EPS), maxY=(int)Math.floor(bounds.maxY - BOUNDS_EPS);
                int minZ=(int)Math.floor(bounds.minZ + BOUNDS_EPS), maxZ=(int)Math.floor(bounds.maxZ - BOUNDS_EPS);
                indexedCells += Math.max(1,maxX-minX+1)*Math.max(1,maxY-minY+1)*Math.max(1,maxZ-minZ+1);
                if (indexedCells > MAX_CELLS * 12) return result;
            }
        }
        return result;
    }

    /** Kept for regression fixtures and older non-model callers; model arcs use final prisms above. */
    private static Map<BlockPos, List<ArcAutoTrim.WorldPrism>> indexCutters(
            List<ArcStationFrames.Station> stations) {
        Map<BlockPos, List<ArcAutoTrim.WorldPrism>> result = new HashMap<>();
        int cells = 0;
        for (int index = 0; index + 1 < stations.size(); index++) {
            Vec3d[] a = ArcStationFrames.section(stations.get(index));
            Vec3d[] b = ArcStationFrames.section(stations.get(index + 1));
            float[] xyz = new float[24];
            for (int i = 0; i < 4; i++) put(xyz, i, a[i]);
            for (int i = 0; i < 4; i++) put(xyz, i + 4, b[i]);
            ArcAutoTrim.WorldPrism cutter = new ArcAutoTrim.WorldPrism(xyz);
            indexCutter(result, cutter);
            Box bounds = cutter.bounds;
            int minX=(int)Math.floor(bounds.minX + BOUNDS_EPS), maxX=(int)Math.floor(bounds.maxX - BOUNDS_EPS);
            int minY=(int)Math.floor(bounds.minY + BOUNDS_EPS), maxY=(int)Math.floor(bounds.maxY - BOUNDS_EPS);
            int minZ=(int)Math.floor(bounds.minZ + BOUNDS_EPS), maxZ=(int)Math.floor(bounds.maxZ - BOUNDS_EPS);
            cells += Math.max(1,maxX-minX+1)*Math.max(1,maxY-minY+1)*Math.max(1,maxZ-minZ+1);
            if (cells > MAX_CELLS * 8) return result;
        }
        return result;
    }

    private static float[] worldBox(BlockPos pos, Box box) {
        double x0 = pos.getX() + box.minX;
        double y0 = pos.getY() + box.minY;
        double z0 = pos.getZ() + box.minZ;
        double x1 = pos.getX() + box.maxX;
        double y1 = pos.getY() + box.maxY;
        double z1 = pos.getZ() + box.maxZ;
        return new float[]{
                (float)x0,(float)y0,(float)z0, (float)x1,(float)y0,(float)z0,
                (float)x1,(float)y1,(float)z0, (float)x0,(float)y1,(float)z0,
                (float)x0,(float)y0,(float)z1, (float)x1,(float)y0,(float)z1,
                (float)x1,(float)y1,(float)z1, (float)x0,(float)y1,(float)z1
        };
    }

    private static void put(float[] xyz, int index, Vec3d value) {
        xyz[index * 3] = (float)value.x;
        xyz[index * 3 + 1] = (float)value.y;
        xyz[index * 3 + 2] = (float)value.z;
    }

    private static boolean intersects(Box first, Box second) {
        return first.maxX > second.minX + BOUNDS_EPS && first.minX < second.maxX - BOUNDS_EPS
                && first.maxY > second.minY + BOUNDS_EPS && first.minY < second.maxY - BOUNDS_EPS
                && first.maxZ > second.minZ + BOUNDS_EPS && first.minZ < second.maxZ - BOUNDS_EPS;
    }

    private static double volume(List<ConvexGeometry.Poly> polys) {
        double result = 0.0;
        for (ConvexGeometry.Poly poly : polys) result += ConvexGeometry.volume(poly);
        return result;
    }

    private record CutResult(List<ConvexGeometry.Poly> remaining,
                             List<ConvexGeometry.Poly> cutters,
                             boolean touched) {}
}
