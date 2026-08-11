import com.slopeconnector.surface.geometry.SegmentChainOrder;
import java.util.*;

public final class SegmentChainOrderHarness {
    private static SegmentChainOrder.Point p(double x,double z){return new SegmentChainOrder.Point(x,0,z);}
    public static void main(String[] args){
        // Long hairpin: the outgoing leg is 0.25 blocks from the incoming leg.  Even with the new
        // seam tolerance 0.08 it must never jump across the hairpin.
        List<SegmentChainOrder.Edge<Integer>> edges=new ArrayList<>();
        int id=0;
        for(int i=0;i<80;i++)edges.add(new SegmentChainOrder.Edge<>(p(i*.25,0),p((i+1)*.25,0),id++));
        edges.add(new SegmentChainOrder.Edge<>(p(20,0),p(20,.25),id++));
        for(int i=80;i>0;i--)edges.add(new SegmentChainOrder.Edge<>(p(i*.25,.25),p((i-1)*.25,.25),id++));
        Collections.shuffle(edges,new Random(926));
        var chain=SegmentChainOrder.order(edges,.08);
        if(chain.size()!=edges.size())throw new AssertionError("chain size "+chain.size()+" / "+edges.size());
        for(int i=1;i<chain.size();i++){
            double d=chain.get(i-1).end().distanceSquared(chain.get(i).start());
            if(d>1e-10)throw new AssertionError("topology jump at "+i+" d="+d);
        }

        // A straight -> circular generator seam can differ by a few hundredths because two float
        // frame calculations meet there.  0.05 must intentionally cluster into one shared node.
        List<SegmentChainOrder.Edge<Integer>> seam=List.of(
                new SegmentChainOrder.Edge<>(p(0,0),p(1,0),1),
                new SegmentChainOrder.Edge<>(p(1.05,0),p(1.4,.2),2));
        var welded=SegmentChainOrder.order(seam,.08);
        if(welded.size()!=2)throw new AssertionError("straight/arc seam not welded");
        if(welded.get(0).end().distanceSquared(welded.get(1).start())>1e-12)
            throw new AssertionError("welded node is not literally shared");

        // But two unrelated arms 0.12 apart remain separate at 0.08.
        if(SegmentChainOrder.touches(p(0,0),p(1,0),p(0,.12),p(1,.12),.08))
            throw new AssertionError("near but unrelated curves were merged");
        System.out.println("segment topology graph + seam weld regression passed");
    }
}
