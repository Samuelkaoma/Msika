package zm.msika.core.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.user.Role;
import zm.msika.core.user.RoleGrant;

/** Policies, deadlines and who may decide (card M-62). */
class RequestRulesTest {

    private static final Set<Role> MASTER = Set.of(Role.MARKET_MASTER);
    private static final Set<Role> ADMIN = Set.of(Role.COUNCIL_ADMIN);

    private static void refused(RequestPolicy p, RequestPolicy minimum) {
        assertThatThrownBy(() -> p.requireAtLeast(minimum)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    @DisplayName("a council can tighten every part of a policy and loosen none")
    void tighten() {
        RequestPolicy min = RequestKind.STALL_SUSPEND.minimum();
        new RequestPolicy(List.of(MASTER, ADMIN, ADMIN), 2, false, 0).requireAtLeast(min);
        refused(new RequestPolicy(List.of(ADMIN), 5, false, 0), min);
        refused(new RequestPolicy(List.of(MASTER, MASTER), 5, false, 0), min);
        refused(new RequestPolicy(List.of(MASTER, Set.of(Role.COUNCIL_ADMIN, Role.REVENUE_OFFICER)), 5, false, 0), min);
        refused(new RequestPolicy(min.steps(), 6, false, 0), min);
        refused(new RequestPolicy(min.steps(), 5, true, 0), min);
        refused(new RequestPolicy(List.of(MASTER), 5, true, 20), new RequestPolicy(List.of(MASTER), 5, true, 10));
        refused(new RequestPolicy(List.of(MASTER), 5, true, 0), new RequestPolicy(List.of(MASTER), 5, true, 10));
        assertThatThrownBy(() -> new RequestPolicy(List.of(Set.of(Role.AUDITOR)), 1, false, 0))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new RequestPolicy(List.of(), 1, false, 0)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    @DisplayName("deadlines skip the weekend and end with the Lusaka day")
    void deadlines() {
        ZoneId lusaka = ZoneId.of("Africa/Lusaka");
        // Friday 20 November 2026, 23:30 in Lusaka: one working day on is Monday.
        assertThat(WorkingDays.due(Instant.parse("2026-11-20T21:30:00Z"), 1, lusaka)).isEqualTo("2026-11-23T22:00:00Z");
        assertThat(WorkingDays.due(Instant.parse("2026-11-24T08:00:00Z"), 5, lusaka)).isEqualTo("2026-12-01T22:00:00Z");
    }

    @Test
    @DisplayName("the raiser, a second approval by one person, the wrong role and the wrong market are refused")
    void deciders() {
        UUID market = UUID.randomUUID();
        RequestPolicy p = new RequestPolicy(List.of(MASTER, ADMIN), 5, false, 0);
        List<RoleGrant> master = List.of(new RoleGrant(Role.MARKET_MASTER, Set.of(market)));
        List<RoleGrant> elsewhere = List.of(new RoleGrant(Role.MARKET_MASTER, Set.of(UUID.randomUUID())));
        List<RoleGrant> admin = List.of(RoleGrant.councilWide(Role.COUNCIL_ADMIN));

        assertThat(DecisionRules.refusal(p, 0, market, false, false, false, master)).isNull();
        assertThat(DecisionRules.refusal(p, 0, market, true, false, false, master)).contains("raised");
        assertThat(DecisionRules.refusal(p, 1, market, false, true, false, admin)).contains("two approvals");
        assertThat(DecisionRules.refusal(p, 0, market, false, false, false, elsewhere)).contains("MARKET_MASTER");
        assertThat(DecisionRules.refusal(p, 0, null, false, false, false, master)).contains("MARKET_MASTER");
        assertThat(DecisionRules.refusal(p, 0, market, false, false, false, admin)).contains("MARKET_MASTER");
        assertThat(DecisionRules.refusal(p, 0, market, false, false, true, admin)).as("escalated").isNull();
        assertThat(DecisionRules.refusal(p, 1, market, false, false, false, master)).contains("COUNCIL_ADMIN");
        assertThat(DecisionRules.refusal(p, 0, market, false, false, false,
                List.of(RoleGrant.councilWide(Role.MSIKA_OPERATOR)))).as("Msika staff decide nothing").isNotNull();
    }

    @Test
    @DisplayName("the sample is rounded up")
    void sample() {
        RequestPolicy p = new RequestPolicy(List.of(MASTER), 5, true, 10);
        assertThat(p.sampleOf(1)).isEqualTo(1);
        assertThat(p.sampleOf(11)).isEqualTo(2);
        assertThat(new RequestPolicy(List.of(MASTER), 5, true, 0).sampleOf(100)).isZero();
    }
}
