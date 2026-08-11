package com.slopeconnector.surface.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders geometric line segments by shared endpoints instead of nearest-neighbour distance.
 *
 * <p>Long/tight arcs can bring two non-adjacent samples physically close to one another. A greedy
 * nearest-endpoint walk can then jump across the curve and make every later station/UV/module
 * assignment wrong. Generated ArcRibbon samples, however, share their true adjacent endpoint to
 * float precision. This utility first clusters only those practically-identical endpoints, builds
 * a graph, and walks graph edges. If a node really has several unused edges, tangent continuity is
 * the tie breaker.</p>
 */
public final class SegmentChainOrder {
    public static final double DEFAULT_ENDPOINT_EPS = 0.01;

    private SegmentChainOrder() {}

    public record Point(double x, double y, double z) {
        public Point subtract(Point other) { return new Point(x-other.x, y-other.y, z-other.z); }
        public double lengthSquared() { return x*x+y*y+z*z; }
        public double distanceSquared(Point other) {
            double dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;
        }
        public Point normalize() {
            double length=Math.sqrt(lengthSquared());
            return length<1.0E-12?new Point(1,0,0):new Point(x/length,y/length,z/length);
        }
        public double dot(Point other) { return x*other.x+y*other.y+z*other.z; }
    }

    public record Edge<T>(Point a, Point b, T value) {}
    public record Oriented<T>(T value, boolean reversed, Point start, Point end) {}

    public static <T> List<Oriented<T>> order(List<Edge<T>> input) {
        return order(input, DEFAULT_ENDPOINT_EPS);
    }

    public static <T> List<Oriented<T>> order(List<Edge<T>> input, double epsilon) {
        if (input.isEmpty()) return List.of();
        Graph<T> graph = build(input, epsilon);
        if (graph.edges.isEmpty()) return List.of();

        Node<T> start = graph.nodes.stream()
                .filter(node -> node.incident.size() == 1)
                .min(Comparator.comparing((Node<T> node) -> node.point.y)
                        .thenComparing(node -> node.point.x)
                        .thenComparing(node -> node.point.z))
                .orElseGet(() -> graph.nodes.stream()
                        .min(Comparator.comparing((Node<T> node) -> node.point.y)
                                .thenComparing(node -> node.point.x)
                                .thenComparing(node -> node.point.z))
                        .orElse(graph.nodes.get(0)));

        boolean[] used = new boolean[graph.edges.size()];
        List<Oriented<T>> result = new ArrayList<>(graph.edges.size());
        Node<T> current = start;
        Point previousDirection = null;

        while (result.size() < graph.edges.size()) {
            GraphEdge<T> selected = null;
            boolean reverse = false;
            double bestScore = -Double.MAX_VALUE;
            for (int edgeIndex : current.incident) {
                if (used[edgeIndex]) continue;
                GraphEdge<T> edge = graph.edges.get(edgeIndex);
                boolean candidateReverse = edge.b == current;
                Node<T> next = candidateReverse ? edge.a : edge.b;
                Point direction = next.point.subtract(current.point).normalize();
                double score = previousDirection == null ? 0.0 : previousDirection.dot(direction);
                // Deterministic secondary score only; topology always wins over physical proximity.
                score += 1.0E-9 * (-(next.point.x*0.17 + next.point.y*0.31 + next.point.z*0.53));
                if (selected == null || score > bestScore) {
                    selected = edge;
                    reverse = candidateReverse;
                    bestScore = score;
                }
            }
            if (selected == null) break;
            int index = selected.index;
            used[index] = true;
            Node<T> next = reverse ? selected.a : selected.b;
            Point startPoint = current.point;
            Point endPoint = next.point;
            result.add(new Oriented<>(selected.value, reverse, startPoint, endPoint));
            previousDirection = endPoint.subtract(startPoint).normalize();
            current = next;
        }
        return List.copyOf(result);
    }

    public static boolean touches(Point a0, Point a1, Point b0, Point b1, double epsilon) {
        double limit=epsilon*epsilon;
        return a0.distanceSquared(b0)<=limit || a0.distanceSquared(b1)<=limit
                || a1.distanceSquared(b0)<=limit || a1.distanceSquared(b1)<=limit;
    }

    private static <T> Graph<T> build(List<Edge<T>> source, double epsilon) {
        List<Node<T>> nodes = new ArrayList<>();
        List<GraphEdge<T>> edges = new ArrayList<>(source.size());
        Map<Cell, List<Node<T>>> buckets = new HashMap<>();
        for (int index=0;index<source.size();index++) {
            Edge<T> sourceEdge=source.get(index);
            Node<T> a=nodeFor(sourceEdge.a,epsilon,nodes,buckets);
            Node<T> b=nodeFor(sourceEdge.b,epsilon,nodes,buckets);
            if (a==b) continue;
            GraphEdge<T> edge=new GraphEdge<>(edges.size(),a,b,sourceEdge.value);
            edges.add(edge);
            a.incident.add(edge.index);
            b.incident.add(edge.index);
        }
        return new Graph<>(nodes,edges);
    }

    private static <T> Node<T> nodeFor(Point point,double epsilon,List<Node<T>> nodes,
                                        Map<Cell,List<Node<T>>> buckets) {
        Cell center=Cell.of(point,epsilon);
        double limit=epsilon*epsilon;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            List<Node<T>> bucket=buckets.get(new Cell(center.x+dx,center.y+dy,center.z+dz));
            if(bucket==null)continue;
            for(Node<T> node:bucket)if(node.point.distanceSquared(point)<=limit)return node;
        }
        Node<T> created=new Node<>(nodes.size(),point);
        nodes.add(created);
        buckets.computeIfAbsent(center,ignored->new ArrayList<>()).add(created);
        return created;
    }

    private static final class Node<T> {
        final int index;
        final Point point;
        final List<Integer> incident=new ArrayList<>(2);
        Node(int index,Point point){this.index=index;this.point=point;}
    }
    private record GraphEdge<T>(int index,Node<T> a,Node<T> b,T value) {}
    private record Graph<T>(List<Node<T>> nodes,List<GraphEdge<T>> edges) {}
    private record Cell(long x,long y,long z) {
        static Cell of(Point point,double epsilon) {
            return new Cell((long)Math.floor(point.x/epsilon),(long)Math.floor(point.y/epsilon),
                    (long)Math.floor(point.z/epsilon));
        }
    }
}
