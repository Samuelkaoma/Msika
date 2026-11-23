package zm.msika.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiTest;
import zm.msika.api.ledger.LedgerService;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.ledger.NewEntry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * The dashboard's figures (card M-27) on a seeded market, each compared with a query written straight against the
 * tables, and the seal check.
 */
class ReportApiTest extends ApiTest {

    record MarketOccupancy(UUID marketId, String code, int stalls, int vacant, int allocated, int suspended, int activeOnDay) {}

    record DayCollections(LocalDate businessDate, String marketCode, long depositedNgwee, long digitalNgwee,
            long declaredNgwee, long levyChargedNgwee, long levyPaidNgwee) {}

    record Arrears(String traderName, String stallCode, int unpaidDays, long unpaidNgwee, LocalDate oldestUnpaid) {}

    record AgentActivity(String name, int confirmed, long confirmedNgwee, int pending, int failed, int cancelled,
            int disputed) {}

    record SealCheck(boolean ok, int seals, int entries, List<String> problems) {}

    record Seal(LocalDate businessDate, int entryCount) {}

    record RuleRequest(String stallClass, Long amountNgwee, String validFrom) {}

    record RunRequest(String date) {}

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 5);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 6);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    LedgerService ledger;

    private Fixture council;
    private UUID marketA;
    private UUID marketB;
    private final UUID[] stalls = new UUID[5];
    private UUID traderOne;
    private UUID traderTwo;
    private UUID agent;

    private String base() {
        return "/api/v1/councils/" + council.councilId();
    }

    private static Instant at(LocalDate day) {
        return day.atTime(15, 0).atZone(LUSAKA).toInstant();
    }

    private LedgerEntry record(int stall, EntryKind kind, long ngwee, LocalDate day, String key) {
        Provenance p = switch (kind) {
            case DEPOSIT -> Provenance.DEPOSITED;
            case DECLARED_SALE -> Provenance.DECLARED;
            default -> Provenance.VERIFIED;
        };
        return ledger.record(new NewEntry(council.councilId(), stalls[stall], kind, p, Money.ofMinor(ngwee, Currency.ZMW),
                at(day), key + "-" + UUID.randomUUID(), null)).entry();
    }

    @BeforeEach
    void seed() {
        council = newCouncil();
        UUID c = council.councilId();
        // The seeded days are in October, so the council must have existed then (no entry predates its council).
        ownerJdbc().sql("update council set created_at = '2026-09-01T00:00:00Z' where id = :c").param("c", c).update();
        marketA = UUID.randomUUID();
        marketB = UUID.randomUUID();
        traderOne = UUID.randomUUID();
        traderTwo = UUID.randomUUID();
        agent = UUID.randomUUID();
        String[][] layout = {{"A", "ALLOCATED"}, {"A", "ALLOCATED"}, {"A", "VACANT"}, {"A", "SUSPENDED"}, {"B", "ALLOCATED"}};
        tenant.inCouncil(c, () -> {
            for (Object[] m : new Object[][] {{marketA, "RPA"}, {marketB, "RPB"}}) {
                jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                                + "values (:id, :c, :code, :code, '20:00', now())")
                        .param("id", m[0]).param("c", c).param("code", m[1]).update();
            }
            for (int i = 0; i < stalls.length; i++) {
                stalls[i] = UUID.randomUUID();
                UUID market = layout[i][0].equals("A") ? marketA : marketB;
                jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                                + "values (:id, :c, :m, :code, 'STANDARD', :st, :t)")
                        .param("id", stalls[i]).param("c", c).param("m", market)
                        .param("code", (market == marketA ? "RPA" : "RPB") + "-S-" + (i + 1)).param("st", layout[i][1])
                        .param("t", UUID.randomUUID().toString().replace("-", "")).update();
            }
            for (Object[] t : new Object[][] {{traderOne, "Trader One", 0}, {traderTwo, "Trader Two", 4}}) {
                jdbc.sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :p, :n)")
                        .param("id", t[0]).param("c", c).param("n", t[1])
                        .param("p", "+260966" + String.format("%06d", Math.floorMod(t[0].hashCode(), 1_000_000))).update();
                jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, :f)")
                        .param("id", UUID.randomUUID()).param("c", c).param("s", stalls[(Integer) t[2]]).param("t", t[0])
                        .param("f", OffsetDateTime.parse("2026-09-01T08:00:00+02:00")).update();
            }
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, :f)")
                    .param("id", UUID.randomUUID()).param("c", c).param("s", stalls[1]).param("t", traderOne)
                    .param("f", OffsetDateTime.parse("2026-09-01T08:00:00+02:00")).update();
            jdbc.sql("insert into agent (id, council_id, msisdn, display_name, active, created_at) values (:id, :c, :p, 'Agent Zed', true, now())")
                    .param("id", agent).param("c", c)
                    .param("p", "+260977" + String.format("%06d", Math.floorMod(agent.hashCode(), 1_000_000))).update();
        });

        // Money on the ledger.
        LedgerEntry dep = record(0, EntryKind.DEPOSIT, 45_000, D1, "dep");
        record(0, EntryKind.MERCHANT_SALE, 2_500, D1, "sale");
        record(1, EntryKind.DECLARED_SALE, 12_000, D1, "declared");
        LedgerEntry wrong = record(4, EntryKind.DEPOSIT, 9_000, D1, "wrong");
        ledger.record(new NewEntry(c, stalls[4], EntryKind.REVERSAL, Provenance.DEPOSITED, Money.ofMinor(-9_000, Currency.ZMW),
                at(D1), "rev-" + UUID.randomUUID(), wrong.id()));
        record(0, EntryKind.LEVY_PAYMENT, 500, D1, "levy");
        record(1, EntryKind.DEPOSIT, 20_000, D2, "dep2");
        assertThat(dep.id()).isPositive();

        // K5 levy a day on both days; stall 0's first day is marked paid.
        api().post(council.adminToken(), base() + "/markets/" + marketA + "/levy-rules", new RuleRequest("STANDARD", 500L, "2026-09-01"), String.class);
        api().post(council.adminToken(), base() + "/markets/" + marketB + "/levy-rules", new RuleRequest("STANDARD", 700L, "2026-09-01"), String.class);
        api().post(council.adminToken(), base() + "/levy-charges/run", new RunRequest(D1.toString()), String.class);
        api().post(council.adminToken(), base() + "/levy-charges/run", new RunRequest(D2.toString()), String.class);

        // Deposits by the agent with each outcome (rows only: the report reads statuses).
        String[] statuses = {"CONFIRMED", "CONFIRMED", "PENDING", "FAILED", "CANCELLED", "DISPUTED"};
        tenant.inCouncil(c, () -> {
            for (int i = 0; i < statuses.length; i++) {
                UUID request = UUID.randomUUID();
                jdbc.sql("insert into payment_request (id, council_id, purpose, stall_id, amount_ngwee, status, created_at, completed_at, failure_reason) "
                                + "values (:id, :c, 'DEPOSIT', :s, 1000, :st, now(), :done, :why)")
                        .param("id", request).param("c", c).param("s", stalls[0])
                        .param("st", statuses[i].equals("PENDING") ? "SENT" : statuses[i].equals("FAILED") ? "FAILED" : "SUCCEEDED")
                        .param("done", statuses[i].equals("PENDING") ? null : OffsetDateTime.now())
                        .param("why", statuses[i].equals("FAILED") ? "INSUFFICIENT_FUNDS" : null).update();
                jdbc.sql("insert into deposit (id, council_id, stall_id, agent_id, amount_ngwee, status, reference, business_date, "
                                + "payment_request_id, ledger_entry_id, status_reason, idempotency_key, created_at, updated_at) "
                                + "values (:id, :c, :s, :a, :amount, :st, :ref, :d, :r, :entry, :why, :k, now(), now())")
                        .param("id", UUID.randomUUID()).param("c", c).param("s", stalls[0]).param("a", agent)
                        .param("amount", 1000L * (i + 1)).param("st", statuses[i]).param("ref", "RP" + "23456789".charAt(i) + "Z")
                        .param("d", D1).param("r", request)
                        .param("entry", List.of("CONFIRMED", "DISPUTED").contains(statuses[i]) ? dep.id() + 0 : null)
                        .param("why", List.of("FAILED", "CANCELLED", "DISPUTED").contains(statuses[i]) ? "test" : null)
                        .param("k", "k" + i).update();
            }
        });
    }

    private long sql(String query) {
        return ownerJdbc().sql(query).param("c", council.councilId()).query(Long.class).single();
    }

    @Test
    @DisplayName("markets: stall counts and active stalls match direct SQL")
    void markets() {
        List<MarketOccupancy> rows = List.of(api().get(council.officerToken(), base() + "/reports/markets?date=" + D1,
                MarketOccupancy[].class).body());

        MarketOccupancy a = rows.stream().filter(r -> r.code().equals("RPA")).findFirst().orElseThrow();
        assertThat(a.stalls()).isEqualTo(sql("select count(*) from stall s join market m on m.id = s.market_id where s.council_id = :c and m.code = 'RPA'"));
        assertThat(a.vacant()).isEqualTo(1);
        assertThat(a.allocated()).isEqualTo(2);
        assertThat(a.suspended()).isEqualTo(1);
        assertThat(a.activeOnDay()).isEqualTo(sql("select count(distinct e.stall_id) from ledger_entry e join stall s on s.id = e.stall_id "
                + "join market m on m.id = s.market_id where e.council_id = :c and m.code = 'RPA' and e.business_date = '2026-10-05' "
                + "and e.kind in ('DEPOSIT','MERCHANT_SALE','DECLARED_SALE')")).isEqualTo(2);
    }

    @Test
    @DisplayName("collections: each provenance apart, reversals netted, levy charged and paid, all matching direct SQL")
    void collections() {
        List<DayCollections> rows = List.of(api().get(council.officerToken(), base() + "/reports/collections?from=" + D1 + "&to=" + D2,
                DayCollections[].class).body());

        DayCollections a1 = rows.stream().filter(r -> r.marketCode().equals("RPA") && r.businessDate().equals(D1)).findFirst().orElseThrow();
        DayCollections b1 = rows.stream().filter(r -> r.marketCode().equals("RPB") && r.businessDate().equals(D1)).findFirst().orElseThrow();
        String inA = "from ledger_entry e join stall s on s.id = e.stall_id join market m on m.id = s.market_id "
                + "where e.council_id = :c and m.code = 'RPA' and e.business_date = '2026-10-05'";
        assertThat(a1.depositedNgwee()).isEqualTo(sql("select coalesce(sum(amount_ngwee),0) " + inA + " and e.kind = 'DEPOSIT'")).isEqualTo(45_000);
        assertThat(a1.digitalNgwee()).isEqualTo(sql("select coalesce(sum(amount_ngwee),0) " + inA + " and e.kind = 'MERCHANT_SALE'"));
        assertThat(a1.declaredNgwee()).isEqualTo(sql("select coalesce(sum(amount_ngwee),0) " + inA + " and e.kind = 'DECLARED_SALE'")).isEqualTo(12_000);
        assertThat(a1.levyPaidNgwee()).isEqualTo(500);
        assertThat(a1.levyChargedNgwee()).isEqualTo(sql("select sum(l.amount_ngwee) from levy_charge l join stall s on s.id = l.stall_id "
                + "join market m on m.id = s.market_id where l.council_id = :c and m.code = 'RPA' and l.business_date = '2026-10-05'"));
        assertThat(b1.depositedNgwee()).as("a reversed deposit counts for nothing").isZero();
        assertThat(rows.stream().mapToLong(DayCollections::depositedNgwee).sum())
                .isEqualTo(sql("select sum(amount_ngwee) from ledger_entry where council_id = :c and kind in ('DEPOSIT', 'REVERSAL')"));
        assertThat(api().get(council.officerToken(), base() + "/reports/collections?from=" + D2 + "&to=" + D1, String.class).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("arrears: unpaid days per trader match direct SQL")
    void arrears() {
        List<Arrears> rows = List.of(api().get(council.officerToken(), base() + "/reports/arrears", Arrears[].class).body());

        assertThat(rows.stream().mapToLong(Arrears::unpaidNgwee).sum())
                .isEqualTo(sql("select sum(amount_ngwee) from levy_charge where council_id = :c and settled_by_instruction_id is null"));
        Arrears two = rows.stream().filter(r -> r.traderName().equals("Trader Two")).findFirst().orElseThrow();
        assertThat(two.unpaidDays()).isEqualTo(2);
        assertThat(two.unpaidNgwee()).isEqualTo(1_400);
        assertThat(two.oldestUnpaid()).isEqualTo(D1);
    }

    @Test
    @DisplayName("agents: deposits by outcome match direct SQL")
    void agents() {
        List<AgentActivity> rows = List.of(api().get(council.officerToken(), base() + "/reports/agents?from=" + D1 + "&to=" + D2,
                AgentActivity[].class).body());

        AgentActivity zed = rows.get(0);
        assertThat(zed.name()).isEqualTo("Agent Zed");
        assertThat(zed.confirmed()).isEqualTo(2);
        assertThat(zed.confirmedNgwee()).isEqualTo(sql("select sum(amount_ngwee) from deposit where council_id = :c and status = 'CONFIRMED'"))
                .isEqualTo(3_000);
        assertThat(List.of(zed.pending(), zed.failed(), zed.cancelled(), zed.disputed())).containsExactly(1, 1, 1, 1);
    }

    @Test
    @DisplayName("seals: listed, and the verify check passes on an untouched ledger")
    void seals() {
        ledger.closeDays(council.councilId(), D2, "test");

        Seal[] seals = api().get(council.officerToken(), base() + "/reports/seals", Seal[].class).body();
        SealCheck check = api().get(council.officerToken(), base() + "/reports/seals/verification", SealCheck.class).body();

        assertThat(seals).isNotEmpty();
        assertThat(seals[0].businessDate()).isEqualTo(D2);
        assertThat(check.ok()).isTrue();
        assertThat(check.problems()).isEmpty();
        assertThat(check.entries()).isEqualTo(sql("select count(*) from ledger_entry where council_id = :c"));
        Fixture other = newCouncil();
        assertThat(api().get(other.adminToken(), base() + "/reports/arrears", String.class).status()).isEqualTo(403);
        assertThat(Map.of()).isEmpty();
    }
}
