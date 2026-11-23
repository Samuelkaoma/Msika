package zm.msika.core.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The checks a draft must pass before it is published (MARKET-MAP section 4). It reports every problem at once, so
 * whoever fixes the draft sees the whole list on the first try, not one problem per attempt.
 */
public final class PlanCheck {

    /** Two footprints sharing a wall may come out this far overlapped when measured, so that much is allowed. */
    public static final int DEFAULT_OVERLAP_TOLERANCE_MM = 50;

    /** One reason the plan cannot be published. {@code stalls} names the stalls concerned, for the drawing to show. */
    public record Blocker(Kind kind, String message, List<String> stalls) {
        public enum Kind {
            BAD_POLYGON, OVERLAP, UNKNOWN_STALL, DUPLICATE_STALL, MISSING_STALL, DUPLICATE_ADDRESS
        }
    }

    private PlanCheck() {}

    /**
     * @param marketStalls every stall of the market, by id, with its code
     */
    public static List<Blocker> check(MarketPlan plan, Map<UUID, String> marketStalls, int overlapToleranceMm) {
        List<Blocker> blockers = new ArrayList<>();
        Map<UUID, String> code = marketStalls;
        java.util.function.Function<UUID, String> name = id -> code.getOrDefault(id, id.toString());

        for (MarketPlan.Section s : plan.sections()) {
            s.polygon().problem().ifPresent(p -> blockers.add(new Blocker(Blocker.Kind.BAD_POLYGON,
                    "section " + s.name() + ": " + p, List.of())));
        }
        for (MarketPlan.Feature f : plan.features()) {
            f.shape().problem().filter(p -> f.shape().corners().size() > 1).ifPresent(p -> blockers.add(new Blocker(
                    Blocker.Kind.BAD_POLYGON, "feature " + f.label() + ": " + p, List.of())));
        }
        List<MarketPlan.Footprint> usable = new ArrayList<>();
        for (MarketPlan.Footprint f : plan.footprints()) {
            var problem = f.polygon().problem();
            if (problem.isPresent()) {
                blockers.add(new Blocker(Blocker.Kind.BAD_POLYGON, "stall " + name.apply(f.stallId()) + ": " + problem.get(),
                        List.of(name.apply(f.stallId()))));
            } else {
                usable.add(f);
            }
        }

        // Footprints that overlap by more than the tolerance. Sorted by x so each is only compared with neighbours.
        usable.sort(Comparator.comparingInt(f -> f.polygon().bounds()[0]));
        for (int i = 0; i < usable.size(); i++) {
            int[] bi = usable.get(i).polygon().bounds();
            for (int j = i + 1; j < usable.size(); j++) {
                if (usable.get(j).polygon().bounds()[0] >= bi[2]) {
                    break;
                }
                long overlap = PolygonMm.overlapMm(usable.get(i).polygon(), usable.get(j).polygon());
                if (overlap > overlapToleranceMm) {
                    String a = name.apply(usable.get(i).stallId());
                    String b = name.apply(usable.get(j).stallId());
                    blockers.add(new Blocker(Blocker.Kind.OVERLAP,
                            "stalls " + a + " and " + b + " overlap by " + overlap + " mm", List.of(a, b)));
                }
            }
        }

        Set<UUID> seen = new HashSet<>();
        Map<String, String> addresses = new HashMap<>();
        for (MarketPlan.Footprint f : plan.footprints()) {
            String n = name.apply(f.stallId());
            if (!marketStalls.containsKey(f.stallId())) {
                blockers.add(new Blocker(Blocker.Kind.UNKNOWN_STALL, "a footprint names stall " + f.stallId()
                        + ", which is not a stall of this market", List.of()));
            }
            if (!seen.add(f.stallId())) {
                blockers.add(new Blocker(Blocker.Kind.DUPLICATE_STALL, "stall " + n + " has more than one footprint",
                        List.of(n)));
            }
            if (f.aisle() != null && f.side() != null) {
                String address = f.aisle() + "/" + f.side() + "/" + f.position();
                String other = addresses.putIfAbsent(address, n);
                if (other != null && !other.equals(n)) {
                    blockers.add(new Blocker(Blocker.Kind.DUPLICATE_ADDRESS, "stalls " + other + " and " + n
                            + " have the same address (aisle " + f.aisle() + ", side " + f.side() + ", position "
                            + f.position() + ")", List.of(other, n)));
                }
            }
        }
        List<String> missing = marketStalls.entrySet().stream()
                .filter(e -> !seen.contains(e.getKey()) && !plan.unmappedStallIds().contains(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        if (!missing.isEmpty()) {
            blockers.add(new Blocker(Blocker.Kind.MISSING_STALL, missing.size() + " stall(s) have no footprint and are "
                    + "not listed as unmapped: " + String.join(", ", missing.subList(0, Math.min(20, missing.size())))
                    + (missing.size() > 20 ? " ..." : ""), missing));
        }
        return List.copyOf(blockers);
    }
}
