package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replicates widened/thickened prism collision into every world block cell it actually occupies.
 * Rendering/topology ignore these proxy prisms; their only purpose is collision coverage beyond the
 * original centerline holder block.
 */
public final class ArcCollisionProxyBuilder {
    private static final int RES = 4; // generation-time compromise; ArcRibbon BE voxelizes again at 1/8.
    private static final int CELLS = RES * RES * RES;
    private static final int MAX_PROXY_BLOCKS = 12000;
    private static final double EPS = 1.0E-6;

    private ArcCollisionProxyBuilder() {}

    public static void rebuild(World world, BlockPos startModelBlock) {
        ArcComponentFinder.Component component = ArcComponentFinder.fromClickedModelBlock(world, startModelBlock);
        if (component == null || component.segments().isEmpty()) return;
        Set<BlockPos> mainHolders = new HashSet<>();
        for (ArcRibbonBlockEntity member : component.members()) mainHolders.add(member.getPos());
        clearOldProxies(world, component, mainHolders);

        // Collision is generated from the SAME canonical shared stations used by visible rendering.
        // Rasterising only the clipped holder prisms was why visual upper/side expansion could exist
        // without collision.  Do not skip main holder cells: append local proxy voxels there too.
        List<ArcStationFrames.Station> stations = new ArrayList<>(ArcStationFrames.build(component));
        if (stations.size() != component.segments().size() + 1) return;
        if (component.startModelBlock() != null
                && world.getBlockEntity(component.startModelBlock()) instanceof ModelBlockEntity startModel) {
            stations.set(0, ArcStationFrames.alignEndpoint(stations.get(0), startModel));
        }
        if (component.endModelBlock() != null
                && world.getBlockEntity(component.endModelBlock()) instanceof ModelBlockEntity endModel) {
            int last=stations.size()-1;
            stations.set(last, ArcStationFrames.alignEndpoint(stations.get(last), endModel));
        }

        Map<BlockPos, BitSet> occupancy = new HashMap<>();
        for (int index=0; index+1<stations.size() && occupancy.size()<=MAX_PROXY_BLOCKS; index++) {
            Vec3d[] a=ArcStationFrames.section(stations.get(index));
            Vec3d[] b=ArcStationFrames.section(stations.get(index+1));
            Vec3d[] vertices=new Vec3d[] {a[0],a[1],a[2],a[3],b[0],b[1],b[2],b[3]};
            addOccupancy(world, vertices, occupancy, null);
        }

        // Endpoint tiles extend half a block OUTSIDE the centerline connection plane and also need
        // proxy collision in every extra cell covered by width/thickness.
        addEndpointOccupancy(world, component.startModelBlock(), occupancy);
        addEndpointOccupancy(world, component.endModelBlock(), occupancy);

        for (Map.Entry<BlockPos,BitSet> entry : occupancy.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            BlockPos pos=entry.getKey();
            BlockState existing=world.getBlockState(pos);
            if (ModelSystemMod.isModelHolder(existing)) continue;
            if (existing.getBlock()!=ArcHotfixMod.ARC_RIBBON && !existing.isAir() && !existing.isReplaceable()) continue;
            List<ArcRibbonBlockEntity.Prism> proxies=mergedBoxes(entry.getValue());
            if (proxies.isEmpty()) continue;
            if (existing.getBlock()!=ArcHotfixMod.ARC_RIBBON) {
                // Client sync only; full neighbour physics across thousands of proxy cells caused
                // generation-time stalls and is unnecessary for an invisible collision holder.
                world.setBlockState(pos,ArcHotfixMod.ARC_RIBBON.getDefaultState(),2);
            }
            BlockEntity be=world.getBlockEntity(pos);
            if (!(be instanceof ArcRibbonBlockEntity ribbon)) continue;
            List<ArcRibbonBlockEntity.Prism> combined=new ArrayList<>();
            boolean hasGroupMarker=false;
            for(ArcRibbonBlockEntity.Prism old:ribbon.getPrisms()) {
                if(!ArcPrismTags.isCollisionProxy(old)) combined.add(old);
                if(ArcPrismTags.isGroupMarker(old)) hasGroupMarker=true;
            }
            if (!hasGroupMarker && component.groupId()!=0L) {
                combined.add(ArcPrismTags.groupMarker(component.groupId(),
                        component.exactWidthSpan(), component.exactRadialSpan(), component.innerFace()));
            }
            combined.addAll(proxies);
            ribbon.setData(ribbon.getSourceState(),combined,new ArrayList<>(ribbon.getSurfaces()));
            world.updateListeners(pos,world.getBlockState(pos),world.getBlockState(pos),2);
        }
    }

    private static void addEndpointOccupancy(World world, BlockPos endpoint,
                                             Map<BlockPos, BitSet> occupancy) {
        if (endpoint == null || !(world.getBlockEntity(endpoint) instanceof ModelBlockEntity model)) return;
        int widthTiles=Math.max(1,Math.min(32,(int)Math.round(model.getEndpointWidthSpan())));
        int radialTiles=Math.max(1,Math.min(32,(int)Math.round(model.getEndpointRadialSpan())));
        if (widthTiles==1 && radialTiles==1) return;
        Vec3d w=model.getEndpointWidthAxis();
        Vec3d r=model.getEndpointRadialAxis();
        if (w.lengthSquared()<1.0E-8) w=new Vec3d(0,0,1); else w=w.normalize();
        if (r.lengthSquared()<1.0E-8) r=new Vec3d(0,1,0); else r=r.normalize();
        Vec3d t=new Vec3d(model.getArcDirection().getOffsetX(),model.getArcDirection().getOffsetY(),model.getArcDirection().getOffsetZ());
        if (t.lengthSquared()<1.0E-8) t=w.crossProduct(r); else t=t.normalize();
        double wCell=model.getEndpointWidthSpan()/widthTiles;
        double rCell=model.getEndpointRadialSpan()/radialTiles;
        Vec3d center0=Vec3d.ofCenter(endpoint);
        for(int wi=0;wi<widthTiles;wi++)for(int ri=0;ri<radialTiles;ri++){
            double wo=((wi+0.5)-widthTiles*0.5)*wCell;
            double ro=((ri+0.5)-radialTiles*0.5)*rCell;
            Vec3d center=center0.add(w.multiply(wo)).add(r.multiply(ro));
            Vec3d tw=t.multiply(0.5), ww=w.multiply(wCell*0.5), rr=r.multiply(rCell*0.5);
            Vec3d[] v=new Vec3d[]{
                    center.subtract(tw).subtract(ww).subtract(rr), center.subtract(tw).add(ww).subtract(rr),
                    center.subtract(tw).add(ww).add(rr), center.subtract(tw).subtract(ww).add(rr),
                    center.add(tw).subtract(ww).subtract(rr), center.add(tw).add(ww).subtract(rr),
                    center.add(tw).add(ww).add(rr), center.add(tw).subtract(ww).add(rr)};
            addOccupancy(world,v,occupancy,endpoint);
        }
    }

    private static void addOccupancy(World world, Vec3d[] vertices,
                                     Map<BlockPos, BitSet> occupancy,
                                     BlockPos endpointToSkip) {
        Plane[] planes=planes(vertices);Bounds bounds=bounds(vertices);
        int minX=(int)Math.floor(bounds.minX-EPS),maxX=(int)Math.floor(bounds.maxX+EPS);
        int minY=(int)Math.floor(bounds.minY-EPS),maxY=(int)Math.floor(bounds.maxY+EPS);
        int minZ=(int)Math.floor(bounds.minZ-EPS),maxZ=(int)Math.floor(bounds.maxZ+EPS);
        for(int bx=minX;bx<=maxX;bx++)for(int by=minY;by<=maxY;by++)for(int bz=minZ;bz<=maxZ;bz++){
            BlockPos pos=new BlockPos(bx,by,bz);
            if(endpointToSkip!=null && pos.equals(endpointToSkip))continue;
            if(ModelSystemMod.isModelHolder(world.getBlockState(pos)))continue;
            BitSet bits=null;
            for(int x=0;x<RES;x++)for(int y=0;y<RES;y++)for(int z=0;z<RES;z++){
                Vec3d point=new Vec3d(bx+(x+0.5)/RES,by+(y+0.5)/RES,bz+(z+0.5)/RES);
                if(!inside(planes,point))continue;
                if(bits==null) bits=occupancy.computeIfAbsent(pos,k->new BitSet(CELLS));
                bits.set(index(x,y,z));
            }
        }
    }

    /**
     * Removes collision-only holders from the previous dimension setting before rebuilding.  Proxy
     * blocks form a contiguous shell around the real topology, so a bounded 26-neighbour flood fill
     * finds them without scanning a huge length x width x height box for long arcs.
     */
    private static void clearOldProxies(World world, ArcComponentFinder.Component component,
                                        Set<BlockPos> mainHolders) {
        // Main holders may also contain proxies from a prior rebuild; strip only those prisms.
        for (ArcRibbonBlockEntity member : component.members()) {
            List<ArcRibbonBlockEntity.Prism> kept = new ArrayList<>();
            boolean changed = false;
            for (ArcRibbonBlockEntity.Prism prism : member.getPrisms()) {
                if (ArcPrismTags.isCollisionProxy(prism)) changed = true;
                else kept.add(prism);
            }
            if (changed) {
                member.setData(member.getSourceState(), kept, new ArrayList<>(member.getSurfaces()));
                world.updateListeners(member.getPos(), world.getBlockState(member.getPos()),
                        world.getBlockState(member.getPos()), 2);
            }
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>(mainHolders);
        for (BlockPos holder : mainHolders) {
            for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
                if (dx==0 && dy==0 && dz==0) continue;
                BlockPos next=holder.add(dx,dy,dz);
                if (visited.add(next)) queue.add(next);
            }
        }
        int examined=0;
        while(!queue.isEmpty() && examined < MAX_PROXY_BLOCKS * 3) {
            BlockPos pos=queue.removeFirst();examined++;
            BlockEntity be=world.getBlockEntity(pos);
            if (!(be instanceof ArcRibbonBlockEntity ribbon) || !proxyOnly(ribbon)) continue;
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(), 2);
            for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
                if (dx==0 && dy==0 && dz==0) continue;
                BlockPos next=pos.add(dx,dy,dz);
                if (visited.add(next)) queue.add(next);
            }
        }
    }

    private static boolean proxyOnly(ArcRibbonBlockEntity ribbon) {
        return ArcPrismTags.isProxyOnly(ribbon);
    }

    private static List<ArcRibbonBlockEntity.Prism> mergedBoxes(BitSet occupied) {
        boolean[] used=new boolean[CELLS];
        List<ArcRibbonBlockEntity.Prism> out=new ArrayList<>();
        for(int y=0;y<RES;y++) for(int z=0;z<RES;z++) for(int x=0;x<RES;x++) {
            int start=index(x,y,z); if(!occupied.get(start)||used[start]) continue;
            int x1=x+1;
            while(x1<RES&&availableLine(occupied,used,x1,y,z,x1+1,y+1,z+1))x1++;
            int z1=z+1;
            while(z1<RES&&availableLine(occupied,used,x,y,z,x1,y+1,z1+1))z1++;
            int y1=y+1;
            while(y1<RES&&availableLine(occupied,used,x,y,z,x1,y1+1,z1))y1++;
            for(int yy=y;yy<y1;yy++)for(int zz=z;zz<z1;zz++)for(int xx=x;xx<x1;xx++)used[index(xx,yy,zz)]=true;
            out.add(ArcPrismTags.collisionProxy(box((double)x/RES,(double)y/RES,(double)z/RES,
                    (double)x1/RES,(double)y1/RES,(double)z1/RES)));
        }
        return out;
    }

    private static boolean availableLine(BitSet occ,boolean[]used,int x0,int y0,int z0,int x1,int y1,int z1){
        for(int y=y0;y<y1;y++)for(int z=z0;z<z1;z++)for(int x=x0;x<x1;x++){
            int i=index(x,y,z);if(!occ.get(i)||used[i])return false;
        }return true;
    }
    private static int index(int x,int y,int z){return (y*RES+z)*RES+x;}
    private static float[] box(double x0,double y0,double z0,double x1,double y1,double z1){
        return new float[]{(float)x0,(float)y0,(float)z0,(float)x1,(float)y0,(float)z0,(float)x1,(float)y1,(float)z0,(float)x0,(float)y1,(float)z0,
                (float)x0,(float)y0,(float)z1,(float)x1,(float)y0,(float)z1,(float)x1,(float)y1,(float)z1,(float)x0,(float)y1,(float)z1};
    }
    private static Vec3d[] worldVertices(BlockPos holder,float[]v){Vec3d[]out=new Vec3d[8];for(int i=0;i<8;i++)out[i]=new Vec3d(holder.getX()+v[i*3],holder.getY()+v[i*3+1],holder.getZ()+v[i*3+2]);return out;}
    private static Bounds bounds(Vec3d[]v){double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;for(Vec3d p:v){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);}return new Bounds(minX,minY,minZ,maxX,maxY,maxZ);}
    private static Plane[] planes(Vec3d[]v){int[][]f={{0,3,2},{4,5,6},{0,1,5},{3,7,6},{0,4,7},{1,2,6}};Vec3d c=Vec3d.ZERO;for(Vec3d p:v)c=c.add(p);c=c.multiply(1.0/8.0);Plane[]out=new Plane[6];for(int i=0;i<6;i++){Vec3d a=v[f[i][0]],b=v[f[i][1]],d=v[f[i][2]];Vec3d n=b.subtract(a).crossProduct(d.subtract(a));if(n.dotProduct(c.subtract(a))>0)n=n.multiply(-1);out[i]=new Plane(n,-n.dotProduct(a));}return out;}
    private static boolean inside(Plane[]planes,Vec3d p){for(Plane plane:planes)if(plane.n.dotProduct(p)+plane.d>EPS)return false;return true;}
    private record Plane(Vec3d n,double d){}
    private record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){}
}
