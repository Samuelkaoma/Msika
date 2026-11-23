package zm.msika.api.levy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiTest;
import zm.msika.api.tenancy.TenantContext;

/** Levy rules and the daily charge (card M-23), checked against the hand-worked table in docs/research/levy.md. */
class LevyChargeApiTest extends ApiTest {

    record RuleRequest(String stallClass, Long amountNgwee, String validFrom) {}

    record RunRequest(String date) {}

    record Run(LocalDate date, int charged, int alreadyCharged, long totalNgwee, List<String> stallsWithoutRule) {}

    record ChargeRow(UUID stallId, String stallCode, UUID traderId, LocalDate businessDate, long amountNgwee) {}

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    private Fixture council;
    private UUID market;

    @BeforeEach
    void setUp() {
        council = newCouncil();
        market = UUID.randomUUID();
        UUID c = council.councilId();
        tenant.inCouncil(c, () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'LVY', 'Levy Test Market', '20:00', now())")
                    .param("id", market).param("c", c).update();
            stall("LVY-A-1", "STANDARD", "2026-11-10T08:00:00+02:00");
            stall("LVY-A-2", "CORNER", "2026-11-10T08:00:00+02:00");
            stall("LVY-A-3", "TABLE", "2026-11-10T08:00:00+02:00");
            stall("LVY-A-4", "STANDARD", null);
            stall("LVY-A-5", "STANDARD", "2026-12-02T10:00:00+02:00");
            stall("LVY-A-6", "KIOSK", "2026-11-10T08:00:00+02:00");
        });
    }

    private void stall(String code, String cls, String heldFrom) {
        UUID c = council.councilId();
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                        + "values (:id, :c, :m, :code, :cls, :status, :token)")
                .param("id", id).param("c", c).param("m", market).param("code", code).param("cls", cls)
                .param("status", heldFrom == null ? "VACANT" : "ALLOCATED")
                .param("token", UUID.randomUUID().toString().replace("-", "")).update();
        if (heldFrom != null) {
            UUID trader = UUID.randomUUID();
            jdbc.sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :p, 'Levy Trader')")
                    .param("id", trader).param("c", c)
                    .param("p", "+260977" + String.format("%06d", Math.floorMod(trader.hashCode(), 1_000_000))).update();
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, :from)")
                    .param("id", UUID.randomUUID()).param("c", c).param("s", id).param("t", trader)
                    .param("from", OffsetDateTime.parse(heldFrom)).update();
        }
    }

    private String rules() {
        return "/api/v1/councils/" + council.councilId() + "/markets/" + market + "/levy-rules";
    }

    private void addHandWorkedRules() {
        for (RuleRequest r : List.of(new RuleRequest("STANDARD", 500L, "2026-12-01"),
                new RuleRequest("CORNER", 800L, "2026-12-01"), new RuleRequest("TABLE", 200L, "2026-12-01"),
                new RuleRequest("STANDARD", 600L, "2026-12-03"))) {
            assertThat(api().post(council.adminToken(), rules(), r, String.class).status()).isEqualTo(201);
        }
    }

    private Run run(String date) {
        return api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/levy-charges/run",
                new RunRequest(date), Run.class).body();
    }

    private Map<String, Long> charges(String date) {
        Map<String, Long> byStall = new LinkedHashMap<>();
        for (ChargeRow row : api().get(council.officerToken(), "/api/v1/councils/" + council.councilId()
                + "/levy-charges?date=" + date, ChargeRow[].class).body()) {
            byStall.put(row.stallCode(), row.amountNgwee());
        }
        return byStall;
    }

    @Test
    @DisplayName("the charges for the seeded market match the table worked out by hand in docs/research/levy.md")
    void handWorkedTable() {
        addHandWorkedRules();

        Run nov30 = run("2026-11-30");
        Run dec1 = run("2026-12-01");
        Run dec2 = run("2026-12-02");
        Run dec3 = run("2026-12-03");

        assertThat(nov30.totalNgwee()).isZero();
        assertThat(nov30.stallsWithoutRule()).containsExactly("LVY-A-1", "LVY-A-2", "LVY-A-3", "LVY-A-6");
        assertThat(charges("2026-12-01")).containsExactly(Map.entry("LVY-A-1", 500L), Map.entry("LVY-A-2", 800L),
                Map.entry("LVY-A-3", 200L));
        assertThat(dec1.stallsWithoutRule()).containsExactly("LVY-A-6");
        assertThat(charges("2026-12-02")).containsExactly(Map.entry("LVY-A-1", 500L), Map.entry("LVY-A-2", 800L),
                Map.entry("LVY-A-3", 200L));
        assertThat(charges("2026-12-03")).containsExactly(Map.entry("LVY-A-1", 600L), Map.entry("LVY-A-2", 800L),
                Map.entry("LVY-A-3", 200L), Map.entry("LVY-A-5", 600L));
        assertThat(List.of(dec1.totalNgwee(), dec2.totalNgwee(), dec3.totalNgwee())).containsExactly(1_500L, 1_500L, 2_200L);
        assertThat(dec1.totalNgwee() + dec2.totalNgwee() + dec3.totalNgwee()).as("K52 over three days").isEqualTo(5_200L);
    }

    @Test
    @DisplayName("running the charge again for a day changes nothing")
    void idempotent() {
        addHandWorkedRules();
        run("2026-12-03");

        Run again = run("2026-12-03");

        assertThat(again.charged()).isZero();
        assertThat(again.alreadyCharged()).isEqualTo(4);
        assertThat(charges("2026-12-03")).hasSize(4);
    }

    @Test
    @DisplayName("an officer reads but cannot add rules; another council sees nothing; a duplicate rule is 409")
    void access() {
        assertThat(api().post(council.officerToken(), rules(), new RuleRequest("STANDARD", 500L, "2026-12-01"),
                String.class).status()).isEqualTo(403);
        addHandWorkedRules();
        assertThat(api().post(council.adminToken(), rules(), new RuleRequest("STANDARD", 999L, "2026-12-01"),
                String.class).status()).isEqualTo(409);
        assertThat(api().post(council.adminToken(), rules(), new RuleRequest("bad class!", 500L, "2026-12-01"),
                String.class).status()).isEqualTo(400);
        assertThat(api().get(council.officerToken(), rules(), Object[].class).body()).hasSize(4);
        Fixture other = newCouncil();
        assertThat(api().get(other.adminToken(), rules(), String.class).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("a rule cannot be changed or deleted: a change is a new rule with a later start")
    void rulesAreAppendOnly() {
        addHandWorkedRules();
        assertThatThrownBy(() -> ownerJdbc().sql("update levy_rule set amount_ngwee = 1 where council_id = :c")
                .param("c", council.councilId()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> ownerJdbc().sql("delete from levy_rule where council_id = :c")
                .param("c", council.councilId()).update()).hasMessageContaining("append-only");
    }
}
