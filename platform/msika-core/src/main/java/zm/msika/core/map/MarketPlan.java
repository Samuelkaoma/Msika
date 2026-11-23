package zm.msika.core.map;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The contents of one version of a market's plan (MARKET-MAP section 4): sections, aisles, features and the stalls'
 * footprints, plus the stalls deliberately left unmapped.
 */
public record MarketPlan(List<Section> sections, List<Aisle> aisles, List<Feature> features, List<Footprint> footprints,
        Set<UUID> unmappedStallIds) {

    /** How a footprint's shape was found (MARKET-MAP rule 2). The map draws each differently. */
    public enum Provenance {
        /** Laid out from the stall numbers: a schematic, not a measurement. */
        GENERATED,
        /** Drawn over a plan image. */
        TRACED,
        /** Measured by walking the market. */
        SURVEYED,
        /** Typed in from known dimensions. */
        STATED
    }

    /** Which side of its aisle a stall faces from. */
    public enum Side {
        L, R
    }

    public enum FeatureKind {
        GATE, TOILET, WATER, OFFICE, DRAIN, OTHER
    }

    public record Section(String name, PolygonMm polygon) {
        public Section {
            Objects.requireNonNull(name);
            Objects.requireNonNull(polygon);
        }
    }

    /** A walkway, as a line through its middle. */
    public record Aisle(String name, List<PointMm> path) {
        public Aisle {
            Objects.requireNonNull(name);
            path = List.copyOf(path);
        }
    }

    public record Feature(FeatureKind kind, String label, PolygonMm shape) {
        public Feature {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(shape);
        }
    }

    /**
     * One stall's shape and address on the plan.
     *
     * @param uncertaintyMm how far the shape may be from the truth, or null when it was not measured (generated)
     * @param aisle the aisle's name, which with side and position is the stall's address
     */
    public record Footprint(UUID stallId, PolygonMm polygon, Provenance provenance, Integer uncertaintyMm, String aisle,
            Side side, int position) {
        public Footprint {
            Objects.requireNonNull(stallId);
            Objects.requireNonNull(polygon);
            Objects.requireNonNull(provenance);
        }
    }

    public MarketPlan {
        sections = List.copyOf(sections);
        aisles = List.copyOf(aisles);
        features = List.copyOf(features);
        footprints = List.copyOf(footprints);
        unmappedStallIds = Set.copyOf(unmappedStallIds);
    }

    public static MarketPlan empty() {
        return new MarketPlan(List.of(), List.of(), List.of(), List.of(), Set.of());
    }
}
