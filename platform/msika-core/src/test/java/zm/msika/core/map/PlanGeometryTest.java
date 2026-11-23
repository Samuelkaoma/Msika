package zm.msika.core.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.map.MarketPlan.Footprint;
import zm.msika.core.map.MarketPlan.Provenance;
import zm.msika.core.map.MarketPlan.Side;
import zm.msika.core.map.PlanCheck.Blocker;

/** Plan geometry, the publish checks and the generated layout (card M-48). */
class PlanGeometryTest {

    @Nested
    @DisplayName("polygon validity")
    class Validity {

        @Test
        void goodPolygons() {
            assertThat(PolygonMm.rectangle(0, 0, 2_500, 2_500).problem()).isEmpty();
            assertThat(PolygonMm.fromFlat(new int[] {0, 0, 3_000, 0, 1_500, 2_000}).problem()).isEmpty();
            assertThat(PolygonMm.rectangle(0, 0, 2_500, 2_000).areaMm2()).isEqualTo(5_000_000L);
        }

        @Test
        @DisplayName("a polygon that crosses itself (a bow tie) is refused")
        void selfCrossing() {
            assertThat(PolygonMm.fromFlat(new int[] {0, 0, 2_000, 2_000, 2_000, 0, 0, 2_000}).problem())
                    .contains("its edges cross");
        }

        @Test
        @DisplayName("fewer than three corners, or no area, is refused")
        void degenerate() {
            assertThat(PolygonMm.fromFlat(new int[] {0, 0, 1_000, 0}).problem()).contains("it has fewer than three corners");
            assertThat(PolygonMm.fromFlat(new int[] {0, 0, 1_000, 0, 2_000, 0}).problem()).contains("it has no area");
        }

        @Test
        @DisplayName("clockwise corners are put counter-clockwise; a concave shape is not convex")
        void orientation() {
            PolygonMm clockwise = PolygonMm.fromFlat(new int[] {0, 0, 0, 1_000, 1_000, 1_000, 1_000, 0});
            assertThat(clockwise.twiceSignedArea()).isNegative();
            assertThat(clockwise.counterClockwise().twiceSignedArea()).isPositive();
            PolygonMm ell = PolygonMm.fromFlat(new int[] {0, 0, 2_000, 0, 2_000, 1_000, 1_000, 1_000, 1_000, 2_000, 0, 2_000});
            assertThat(ell.problem()).isEmpty();
            assertThat(ell.isConvex()).isFalse();
        }
    }

    @Nested
    @DisplayName("overlap and its 50 mm tolerance")
    class Overlap {

        private final PolygonMm a = PolygonMm.rectangle(0, 0, 2_500, 2_500);

        @Test
        void touchingIsNotOverlapping() {
            assertThat(PolygonMm.overlapMm(a, PolygonMm.rectangle(2_500, 0, 2_500, 2_500))).isZero();
            assertThat(PolygonMm.overlapMm(a, PolygonMm.rectangle(3_000, 0, 2_500, 2_500))).isZero();
        }

        @Test
        void measuredOverlap() {
            assertThat(PolygonMm.overlapMm(a, PolygonMm.rectangle(2_460, 0, 2_500, 2_500))).isEqualTo(40);
            assertThat(PolygonMm.overlapMm(a, PolygonMm.rectangle(2_440, 0, 2_500, 2_500))).isEqualTo(60);
            assertThat(PolygonMm.overlapMm(a, PolygonMm.fromFlat(new int[] {1_000, 1_000, 4_000, 1_000, 2_500, 4_000})))
                    .isPositive();
        }

        @Test
        @DisplayName("40 mm is within the tolerance and passes; 60 mm is over it and blocks; with no tolerance both block")
        void tolerance() {
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            Map<UUID, String> stalls = Map.of(s1, "SOW-A-1", s2, "SOW-A-2");
            MarketPlan within = plan(new Footprint(s1, a, Provenance.SURVEYED, 30, "A-1", Side.L, 1),
                    new Footprint(s2, PolygonMm.rectangle(2_460, 0, 2_500, 2_500), Provenance.SURVEYED, 30, "A-1", Side.L, 2));
            MarketPlan over = plan(new Footprint(s1, a, Provenance.SURVEYED, 30, "A-1", Side.L, 1),
                    new Footprint(s2, PolygonMm.rectangle(2_440, 0, 2_500, 2_500), Provenance.SURVEYED, 30, "A-1", Side.L, 2));

            assertThat(PlanCheck.check(within, stalls, PlanCheck.DEFAULT_OVERLAP_TOLERANCE_MM)).isEmpty();
            assertThat(PlanCheck.check(over, stalls, PlanCheck.DEFAULT_OVERLAP_TOLERANCE_MM))
                    .extracting(Blocker::kind).containsExactly(Blocker.Kind.OVERLAP);
            assertThat(PlanCheck.check(within, stalls, 0)).extracting(Blocker::kind).containsExactly(Blocker.Kind.OVERLAP);
        }
    }

    private static MarketPlan plan(Footprint... footprints) {
        return new MarketPlan(List.of(), List.of(), List.of(), List.of(footprints), Set.of());
    }

    @Test
    @DisplayName("a draft with four different problems is refused with all four listed at once")
    void fourBlockers() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Map<UUID, String> stalls = Map.of(s1, "SOW-A-1", s2, "SOW-A-2", s3, "SOW-A-3", missing, "SOW-A-4");
        MarketPlan draft = plan(
                new Footprint(s1, PolygonMm.fromFlat(new int[] {0, 0, 2_000, 2_000, 2_000, 0, 0, 2_000}), Provenance.TRACED,
                        100, "A-1", Side.L, 1),
                new Footprint(s2, PolygonMm.rectangle(10_000, 0, 2_500, 2_500), Provenance.TRACED, 100, "A-1", Side.L, 2),
                new Footprint(s3, PolygonMm.rectangle(11_000, 0, 2_500, 2_500), Provenance.TRACED, 100, "A-1", Side.L, 3),
                new Footprint(stranger, PolygonMm.rectangle(30_000, 0, 2_500, 2_500), Provenance.TRACED, 100, "A-1", Side.R, 1));

        List<Blocker> blockers = PlanCheck.check(draft, stalls, PlanCheck.DEFAULT_OVERLAP_TOLERANCE_MM);

        assertThat(blockers).extracting(Blocker::kind).containsExactlyInAnyOrder(Blocker.Kind.BAD_POLYGON,
                Blocker.Kind.OVERLAP, Blocker.Kind.UNKNOWN_STALL, Blocker.Kind.MISSING_STALL);
        assertThat(blockers).filteredOn(b -> b.kind() == Blocker.Kind.OVERLAP).singleElement()
                .satisfies(b -> assertThat(b.stalls()).containsExactly("SOW-A-2", "SOW-A-3"));
        assertThat(blockers).filteredOn(b -> b.kind() == Blocker.Kind.MISSING_STALL).singleElement()
                .satisfies(b -> assertThat(b.stalls()).containsExactly("SOW-A-4"));
    }

    @Test
    @DisplayName("two footprints for one stall, or two stalls at one address, are refused; a listed unmapped stall is fine")
    void duplicates() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        Map<UUID, String> stalls = Map.of(s1, "SOW-A-1", s2, "SOW-A-2", s3, "SOW-A-3");
        MarketPlan draft = new MarketPlan(List.of(), List.of(), List.of(), List.of(
                new Footprint(s1, PolygonMm.rectangle(0, 0, 2_500, 2_500), Provenance.GENERATED, null, "A-1", Side.L, 1),
                new Footprint(s1, PolygonMm.rectangle(5_000, 0, 2_500, 2_500), Provenance.GENERATED, null, "A-1", Side.L, 2),
                new Footprint(s2, PolygonMm.rectangle(10_000, 0, 2_500, 2_500), Provenance.GENERATED, null, "A-1", Side.L, 2)),
                Set.of(s3));

        assertThat(PlanCheck.check(draft, stalls, 50)).extracting(Blocker::kind)
                .containsExactlyInAnyOrder(Blocker.Kind.DUPLICATE_STALL, Blocker.Kind.DUPLICATE_ADDRESS);
    }

    @Test
    @DisplayName("a generated plan for a 300-stall market places every stall, with no overlaps and no duplicate addresses")
    void generated300() {
        Map<UUID, String> stalls = new HashMap<>();
        String[] sections = {"A", "B", "C", "D"};
        for (int i = 0; i < 300; i++) {
            stalls.put(UUID.randomUUID(), "SOW-" + sections[i % 4] + "-" + (i / 4 + 1));
        }

        MarketPlan plan = LayoutGenerator.generate(stalls, LayoutGenerator.Config.standard());

        assertThat(plan.footprints()).hasSize(300);
        assertThat(plan.unmappedStallIds()).isEmpty();
        assertThat(plan.footprints()).allMatch(f -> f.provenance() == Provenance.GENERATED && f.uncertaintyMm() == null);
        assertThat(PlanCheck.check(plan, stalls, 0)).as("not even touching overlaps").isEmpty();
        Set<String> addresses = new HashSet<>();
        plan.footprints().forEach(f -> assertThat(addresses.add(f.aisle() + f.side() + f.position())).isTrue());
        assertThat(plan.sections()).extracting(MarketPlan.Section::name).containsExactly("A", "B", "C", "D");
        // 75 stalls a section, 30 a side (60 an aisle): two aisles each.
        assertThat(plan.aisles()).hasSize(8);
        // Footprints sit inside their section.
        for (MarketPlan.Footprint f : plan.footprints()) {
            int[] fb = f.polygon().bounds();
            int[] sb = plan.sections().stream().filter(s -> f.aisle().startsWith(s.name() + "-")).findFirst()
                    .orElseThrow().polygon().bounds();
            assertThat(fb[0] >= sb[0] && fb[1] >= sb[1] && fb[2] <= sb[2] && fb[3] <= sb[3]).isTrue();
        }
    }

    @Test
    @DisplayName("numbers run in numeric order (9 before 10), and codes that do not fit the pattern are left unmapped")
    void ordering() {
        UUID nine = UUID.randomUUID();
        UUID ten = UUID.randomUUID();
        UUID odd = UUID.randomUUID();
        MarketPlan plan = LayoutGenerator.generate(Map.of(ten, "SOW-A-10", nine, "SOW-A-9", odd, "KIOSK-7"),
                new LayoutGenerator.Config(30, 2_500, 2_500, 2_000, 4_000));

        assertThat(plan.footprints()).extracting(Footprint::stallId).containsExactly(nine, ten);
        assertThat(plan.unmappedStallIds()).containsExactly(odd);
    }
}
