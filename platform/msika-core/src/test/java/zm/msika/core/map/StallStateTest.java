package zm.msika.core.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.map.StallState.LevyBand;
import zm.msika.core.map.StallState.State;

/** Each state and each levy band, with the edges (card M-49). */
class StallStateTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 10);

    private static State held(LocalDate last) {
        return StallState.state("ALLOCATED", last, DAY, StallState.DEFAULT_QUIET_DAYS);
    }

    @Test
    void freeAndSuspended() {
        assertThat(StallState.state("VACANT", DAY, DAY, 7)).isEqualTo(State.FREE);
        assertThat(StallState.state("SUSPENDED", DAY, DAY, 7)).isEqualTo(State.SUSPENDED);
    }

    @Test
    @DisplayName("active: trade today or within the last 6 days; quiet: last trade exactly 7 days ago, or never")
    void activeAndQuietEdges() {
        assertThat(held(DAY)).isEqualTo(State.ACTIVE);
        assertThat(held(DAY.minusDays(6))).as("6 days ago is inside the 7-day window").isEqualTo(State.ACTIVE);
        assertThat(held(DAY.minusDays(7))).as("exactly 7 days quiet").isEqualTo(State.QUIET);
        assertThat(held(DAY.minusDays(30))).isEqualTo(State.QUIET);
        assertThat(held(null)).as("never traded").isEqualTo(State.QUIET);
        assertThat(held(DAY.plusDays(1))).as("trade after the day drawn does not count").isEqualTo(State.QUIET);
    }

    @Test
    @DisplayName("the quiet period is configurable")
    void configurable() {
        assertThat(StallState.state("ALLOCATED", DAY.minusDays(7), DAY, 14)).isEqualTo(State.ACTIVE);
        assertThat(StallState.state("ALLOCATED", DAY.minusDays(2), DAY, 2)).isEqualTo(State.QUIET);
    }

    @Test
    @DisplayName("levy bands: paid up, 1 to 7 days owed (7 included), more than 7, none for a free stall")
    void levyBands() {
        assertThat(StallState.levyBand(true, 0)).isEqualTo(LevyBand.PAID_UP);
        assertThat(StallState.levyBand(true, 1)).isEqualTo(LevyBand.OWED_1_TO_7);
        assertThat(StallState.levyBand(true, 7)).isEqualTo(LevyBand.OWED_1_TO_7);
        assertThat(StallState.levyBand(true, 8)).isEqualTo(LevyBand.OWED_OVER_7);
        assertThat(StallState.levyBand(false, 3)).isEqualTo(LevyBand.NONE);
    }
}
