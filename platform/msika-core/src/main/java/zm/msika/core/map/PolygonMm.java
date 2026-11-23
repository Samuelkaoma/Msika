package zm.msika.core.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A polygon in plan millimetres: its corners in order, closed implicitly (the last corner joins the first). Stored as
 * {@code x1, y1, x2, y2, ...} (MARKET-MAP section 10). All the arithmetic is on {@code long}, so nothing overflows for
 * any market that fits on Earth.
 *
 * <p>A polygon can be built from anything; {@link #problem()} says whether it is usable on a plan.
 */
public final class PolygonMm {

    private final List<PointMm> corners;

    private PolygonMm(List<PointMm> corners) {
        this.corners = List.copyOf(corners);
    }

    public static PolygonMm of(List<PointMm> corners) {
        return new PolygonMm(corners);
    }

    /** From {@code x1, y1, x2, y2, ...}. */
    public static PolygonMm fromFlat(int[] xy) {
        if (xy.length % 2 != 0) {
            throw new IllegalArgumentException("a polygon needs an even number of coordinates");
        }
        List<PointMm> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            points.add(new PointMm(xy[i], xy[i + 1]));
        }
        return new PolygonMm(points);
    }

    /** An axis-aligned rectangle from its lower-left corner, counter-clockwise. */
    public static PolygonMm rectangle(int x, int y, int width, int height) {
        return new PolygonMm(List.of(new PointMm(x, y), new PointMm(x + width, y), new PointMm(x + width, y + height),
                new PointMm(x, y + height)));
    }

    public List<PointMm> corners() {
        return corners;
    }

    public int[] toFlat() {
        int[] xy = new int[corners.size() * 2];
        for (int i = 0; i < corners.size(); i++) {
            xy[2 * i] = corners.get(i).x();
            xy[2 * i + 1] = corners.get(i).y();
        }
        return xy;
    }

    /** Twice the signed area (shoelace): positive when the corners run counter-clockwise. */
    public long twiceSignedArea() {
        long sum = 0;
        for (int i = 0; i < corners.size(); i++) {
            PointMm a = corners.get(i);
            PointMm b = corners.get((i + 1) % corners.size());
            sum += (long) a.x() * b.y() - (long) b.x() * a.y();
        }
        return sum;
    }

    /** The area in square millimetres, rounded down. */
    public long areaMm2() {
        return Math.abs(twiceSignedArea()) / 2;
    }

    /** The same shape with its corners counter-clockwise, which is how plans store them. */
    public PolygonMm counterClockwise() {
        if (twiceSignedArea() >= 0) {
            return this;
        }
        List<PointMm> reversed = new ArrayList<>(corners);
        java.util.Collections.reverse(reversed);
        return new PolygonMm(reversed);
    }

    /** Why the polygon cannot be used on a plan, or empty if it can. */
    public Optional<String> problem() {
        if (corners.size() < 3) {
            return Optional.of("it has fewer than three corners");
        }
        int n = corners.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean neighbours = j == i + 1 || (i == 0 && j == n - 1);
                if (!neighbours && segmentsTouch(corners.get(i), corners.get((i + 1) % n), corners.get(j),
                        corners.get((j + 1) % n))) {
                    return Optional.of("its edges cross");
                }
            }
        }
        // After the crossing check: a symmetric bow tie also has zero signed area, but "its edges cross" is the
        // true reason.
        if (twiceSignedArea() == 0) {
            return Optional.of("it has no area");
        }
        return Optional.empty();
    }

    public boolean isConvex() {
        int n = corners.size();
        int sign = 0;
        for (int i = 0; i < n; i++) {
            long c = cross(corners.get(i), corners.get((i + 1) % n), corners.get((i + 2) % n));
            if (c != 0) {
                int s = c > 0 ? 1 : -1;
                if (sign != 0 && s != sign) {
                    return false;
                }
                sign = s;
            }
        }
        return true;
    }

    /** The smallest axis-aligned box around the polygon: {minX, minY, maxX, maxY}. */
    public int[] bounds() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PointMm p : corners) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    /**
     * How far two polygons overlap, in millimetres: the narrower side of the box around the area they share, or 0 if
     * they share no area. Two stalls that only touch along a wall overlap by 0.
     *
     * <p>The shared area is found by clipping one polygon with the other, which is exact when the clipping polygon is
     * convex (stall footprints almost always are). When neither is convex the boxes around them are compared instead,
     * which can only report more overlap than there is, never less: a publish check errs on the side of a person
     * looking.
     */
    public static long overlapMm(PolygonMm a, PolygonMm b) {
        int[] ba = a.bounds();
        int[] bb = b.bounds();
        if (ba[2] <= bb[0] || bb[2] <= ba[0] || ba[3] <= bb[1] || bb[3] <= ba[1]) {
            return 0;
        }
        List<double[]> shared;
        if (b.isConvex()) {
            shared = clip(a, b);
        } else if (a.isConvex()) {
            shared = clip(b, a);
        } else {
            return Math.min(Math.min(ba[2], bb[2]) - Math.max(ba[0], bb[0]), Math.min(ba[3], bb[3]) - Math.max(ba[1], bb[1]));
        }
        if (shared.size() < 3 || Math.abs(area(shared)) < 1) {
            return 0;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : shared) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return Math.round(Math.min(maxX - minX, maxY - minY));
    }

    /** Sutherland-Hodgman: the part of {@code subject} inside the convex {@code clipper}. */
    private static List<double[]> clip(PolygonMm subject, PolygonMm clipper) {
        List<double[]> out = new ArrayList<>();
        for (PointMm p : subject.corners) {
            out.add(new double[] {p.x(), p.y()});
        }
        List<PointMm> c = clipper.counterClockwise().corners;
        for (int i = 0; i < c.size() && !out.isEmpty(); i++) {
            PointMm e1 = c.get(i);
            PointMm e2 = c.get((i + 1) % c.size());
            List<double[]> in = out;
            out = new ArrayList<>();
            for (int j = 0; j < in.size(); j++) {
                double[] cur = in.get(j);
                double[] prev = in.get((j + in.size() - 1) % in.size());
                boolean curIn = side(e1, e2, cur) >= 0;
                boolean prevIn = side(e1, e2, prev) >= 0;
                if (curIn) {
                    if (!prevIn) {
                        out.add(intersect(prev, cur, e1, e2));
                    }
                    out.add(cur);
                } else if (prevIn) {
                    out.add(intersect(prev, cur, e1, e2));
                }
            }
        }
        return out;
    }

    private static double side(PointMm a, PointMm b, double[] p) {
        return (double) (b.x() - a.x()) * (p[1] - a.y()) - (double) (b.y() - a.y()) * (p[0] - a.x());
    }

    private static double[] intersect(double[] p, double[] q, PointMm a, PointMm b) {
        double a1 = side(a, b, p);
        double a2 = side(a, b, q);
        double t = a1 / (a1 - a2);
        return new double[] {p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])};
    }

    private static double area(List<double[]> ps) {
        double sum = 0;
        for (int i = 0; i < ps.size(); i++) {
            double[] a = ps.get(i);
            double[] b = ps.get((i + 1) % ps.size());
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum / 2;
    }

    private static long cross(PointMm o, PointMm a, PointMm b) {
        return (long) (a.x() - o.x()) * (b.y() - o.y()) - (long) (a.y() - o.y()) * (b.x() - o.x());
    }

    private static boolean segmentsTouch(PointMm p1, PointMm p2, PointMm q1, PointMm q2) {
        long d1 = cross(q1, q2, p1);
        long d2 = cross(q1, q2, p2);
        long d3 = cross(p1, p2, q1);
        long d4 = cross(p1, p2, q2);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && onSegment(q1, q2, p1)) || (d2 == 0 && onSegment(q1, q2, p2))
                || (d3 == 0 && onSegment(p1, p2, q1)) || (d4 == 0 && onSegment(p1, p2, q2));
    }

    private static boolean onSegment(PointMm a, PointMm b, PointMm p) {
        return Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.y(), b.y()) <= p.y() && p.y() <= Math.max(a.y(), b.y());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PolygonMm p && p.corners.equals(corners);
    }

    @Override
    public int hashCode() {
        return corners.hashCode();
    }

    @Override
    public String toString() {
        return "PolygonMm" + corners;
    }
}
