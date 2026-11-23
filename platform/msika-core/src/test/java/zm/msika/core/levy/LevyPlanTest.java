package zm.msika.core.levy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which rule applies on a day, and what each held stall is charged (card M-23). */
class LevyPlanTest {

    private final UUID council = UUID.randomUUID();
    private final UUID market = UUID.randomUUID();
    private final UUID otherMarket = UUID.randomUUID();

    private LevyRule rule(UUID m, String cls, long ngwee, String from) {
        return new LevyRule(UUID.randomUUID(), council, m, cls, ngwee, LocalDate.parse(from));
    }

    @Test
    @DisplayName("the rule in force is the latest one starting on or before the day, for that market and class")
    void ruleInForce() {
        LevyRule old = rule(market, "STANDARD", 500, "2026-12-01");
        LevyRule raised = rule(market, "STANDARD", 600, "2026-12-03");
        List<LevyRule> rules = List.of(raised, old, rule(otherMarket, "STANDARD", 900, "2026-11-01"),
                rule(market, "CORNER", 800, "2026-11-01"));

        assertThat(LevyPlan.ruleFor(rules, market, "STANDARD", LocalDate.parse("2026-11-30"))).isEmpty();
        assertThat(LevyPlan.ruleFor(rules, market, "STANDARD", LocalDate.parse("2026-12-01"))).contains(old);
        assertThat(LevyPlan.ruleFor(rules, market, "STANDARD", LocalDate.parse("2026-12-02"))).contains(old);
        assertThat(LevyPlan.ruleFor(rules, market, "STANDARD", LocalDate.parse("2026-12-03"))).contains(raised);
        assertThat(LevyPlan.ruleFor(rules, market, "STANDARD", LocalDate.parse("2027-06-01"))).contains(raised);
    }

    @Test
    @DisplayName("held stalls with a rule are charged; those without are listed, not charged")
    void plan() {
        UUID trader = UUID.randomUUID();
        LevyPlan.HeldStall standard = new LevyPlan.HeldStall(UUID.randomUUID(), market, "STANDARD", trader);
        LevyPlan.HeldStall kiosk = new LevyPlan.HeldStall(UUID.randomUUID(), market, "KIOSK", trader);
        LevyRule r = rule(market, "STANDARD", 500, "2026-12-01");

        LevyPlan.Plan plan = LevyPlan.plan(LocalDate.parse("2026-12-01"), List.of(standard, kiosk), List.of(r));

        assertThat(plan.charges()).containsExactly(new LevyPlan.Charge(standard.stallId(), trader, 500, r.id()));
        assertThat(plan.withoutRule()).containsExactly(kiosk);
    }

    @Test
    @DisplayName("a levy of zero or less is not a rule")
    void positive() {
        assertThatThrownBy(() -> rule(market, "STANDARD", 0, "2026-12-01")).isInstanceOf(IllegalArgumentException.class);
    }
}
