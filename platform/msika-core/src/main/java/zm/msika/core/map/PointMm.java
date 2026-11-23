package zm.msika.core.map;

/**
 * A point in a market's own flat plane, in whole millimetres (MARKET-MAP rule 5, decision 0006). x grows to the right
 * (east-ish), y grows up (north-ish). Integers, like money in ngwee: no rounding surprises, and the same point is
 * always the same point.
 */
public record PointMm(int x, int y) {}
