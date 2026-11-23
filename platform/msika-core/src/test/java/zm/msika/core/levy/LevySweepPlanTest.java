package zm.msika.core.levy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which levy days a deposit pays (card M-24). */
class LevySweepPlanTest {

    private static LevySweepPlan.Unpaid day(String date, long ngwee) {
        return new LevySweepPlan.Unpaid(UUID.randomUUID(), LocalDate.parse(date), ngwee);
    }

    private final LevySweepPlan.Unpaid d1 = day("2026-12-01", 500);
    private final LevySweepPlan.Unpaid d2 = day("2026-12-02", 500);
    private final LevySweepPlan.Unpaid d3 = day("2026-12-03", 600);

    @Test
    @DisplayName("a deposit that covers everything pays every day")
    void all() {
        assertThat(LevySweepPlan.choose(List.of(d3, d1, d2), 45_000)).containsExactly(d1, d2, d3);
    }

    @Test
    @DisplayName("a deposit smaller than the levy owed pays the oldest whole days it covers")
    void smaller() {
        assertThat(LevySweepPlan.choose(List.of(d3, d2, d1), 1_200)).containsExactly(d1, d2);
        assertThat(LevySweepPlan.total(LevySweepPlan.choose(List.of(d3, d2, d1), 1_200))).isEqualTo(1_000);
    }

    @Test
    @DisplayName("a deposit smaller than one day's levy pays nothing; nothing owed pays nothing")
    void nothing() {
        assertThat(LevySweepPlan.choose(List.of(d1), 499)).isEmpty();
        assertThat(LevySweepPlan.choose(List.of(), 45_000)).isEmpty();
    }

    @Test
    @DisplayName("an older day that does not fit stops the sweep: days are paid in order, never skipped")
    void inOrder() {
        LevySweepPlan.Unpaid big = day("2026-11-30", 2_000);
        assertThat(LevySweepPlan.choose(List.of(d1, big), 1_000)).isEmpty();
    }
}
