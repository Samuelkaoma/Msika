package zm.msika.core.levy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Works out a day's levy charges (card M-23). Pure: given the stalls that were held at the start of the day and the
 * market's rules, it says what each stall owes, and which stalls have no rule (so a person can add one).
 *
 * <p>A stall is charged to whoever held it when the trading day began, at the rule in force that day: the rule for
 * its market and class with the latest start date on or before the day. A stall allocated during the day is first
 * charged the next day.
 */
public final class LevyPlan {

    /** A stall held at the start of the day. */
    public record HeldStall(UUID stallId, UUID marketId, String stallClass, UUID traderId) {}

    /** One stall's levy for the day. */
    public record Charge(UUID stallId, UUID traderId, long amountNgwee, UUID ruleId) {}

    /** The day's charges, and the stalls no rule covers. */
    public record Plan(LocalDate date, List<Charge> charges, List<HeldStall> withoutRule) {}

    private LevyPlan() {}

    /** The rule in force for a market and class on a day, if any. */
    public static Optional<LevyRule> ruleFor(Collection<LevyRule> rules, UUID marketId, String stallClass, LocalDate day) {
        return rules.stream()
                .filter(r -> r.marketId().equals(marketId) && r.stallClass().equals(stallClass) && !r.validFrom().isAfter(day))
                .max(Comparator.comparing(LevyRule::validFrom));
    }

    public static Plan plan(LocalDate day, Collection<HeldStall> held, Collection<LevyRule> rules) {
        Objects.requireNonNull(day);
        List<Charge> charges = new ArrayList<>();
        List<HeldStall> without = new ArrayList<>();
        for (HeldStall s : held) {
            Optional<LevyRule> rule = ruleFor(rules, s.marketId(), s.stallClass(), day);
            if (rule.isPresent()) {
                charges.add(new Charge(s.stallId(), s.traderId(), rule.get().amountNgwee(), rule.get().id()));
            } else {
                without.add(s);
            }
        }
        return new Plan(day, List.copyOf(charges), List.copyOf(without));
    }
}
