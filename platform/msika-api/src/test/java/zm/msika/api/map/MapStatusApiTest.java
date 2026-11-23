package zm.msika.api.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiTest;
import zm.msika.api.ledger.LedgerService;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.map.StallState;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/** The map's status, panel and search (card M-49). */
class MapStatusApiTest extends ApiTest {

    record StallStatus(UUID stallId, String code, String state, String levy, int unpaidLevyDays, long depositedTodayNgwee,
            int openDisputes, String traderName, String traderPhone) {}

    record MarketStatus(LocalDate date, List<StallStatus> stalls, Map<String, Integer> counts, Map<String, Integer> levyCounts) {}

    record Hit(UUID traderId, String traderName, String traderPhone, UUID stallId, String stallCode) {}

    record Holder(String name, String phone) {}

    record DayAmount(LocalDate date, long depositedNgwee) {}

    record Panel(String code, String status, Holder holder, List<DayAmount> lastSevenDays, long depositedTodayNgwee,
            int unpaidLevyDays, long unpaidLevyNgwee, List<Holder> history) {}

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    LedgerService ledger;

    @Autowired
    MapStatusService service;

    private Fixture council;
    private UUID market;

    private String base() {
        return "/api/v1/councils/" + council.councilId();
    }

    /** A council that existed long before the seeded days, and one market. */
    private void seedCouncil() {
        council = newCouncil();
        ownerJdbc().sql("update council set created_at = '2026-01-01T00:00:00Z' where id = :c").param("c", council.councilId()).update();
        market = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                        + "values (:id, :c, 'MST', 'Map Status Market', '20:00', now())")
                .param("id", market).param("c", council.councilId()).update());
    }

    private UUID stall(String code, String status, String traderName, String phone) {
        UUID id = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> {
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) values (:id, :c, :m, :code, 'STANDARD', :st, :t)")
                    .param("id", id).param("c", council.councilId()).param("m", market).param("code", code).param("st", status)
                    .param("t", UUID.randomUUID().toString().replace("-", "")).update();
            if (traderName != null) {
                UUID trader = UUID.randomUUID();
                jdbc.sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :p, :n)")
                        .param("id", trader).param("c", council.councilId()).param("p", phone).param("n", traderName).update();
                jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, :f)")
                        .param("id", UUID.randomUUID()).param("c", council.councilId()).param("s", id).param("t", trader)
                        .param("f", OffsetDateTime.parse("2026-02-01T08:00:00+02:00")).update();
            }
        });
        return id;
    }

    private void deposit(UUID stall, LocalDate day, long ngwee) {
        ledger.record(new NewEntry(council.councilId(), stall, EntryKind.DEPOSIT, Provenance.DEPOSITED, Money.ofMinor(ngwee, Currency.ZMW),
                day.atTime(15, 0).atZone(LUSAKA).toInstant(), "dep-" + UUID.randomUUID(), null));
    }

    private void unpaidLevy(UUID stall, int days) {
        tenant.inCouncil(council.councilId(), () -> {
            UUID rule = UUID.randomUUID();
            jdbc.sql("insert into levy_rule (id, council_id, market_id, stall_class, amount_ngwee, valid_from, created_at, created_by) "
                            + "values (:id, :c, :m, 'STANDARD', 500, :f, now(), 'test') on conflict do nothing")
                    .param("id", rule).param("c", council.councilId()).param("m", market).param("f", LocalDate.of(2026, 1, 1).plusDays(
                            ThreadLocalRandom.current().nextInt(100_000))).update();
            UUID trader = jdbc.sql("select trader_id from stall_allocation where stall_id = :s").param("s", stall).query(UUID.class).single();
            for (int i = 0; i < days; i++) {
                jdbc.sql("insert into levy_charge (id, council_id, stall_id, trader_id, business_date, amount_ngwee, rule_id, created_at) "
                                + "values (:id, :c, :s, :t, :d, 500, :r, now())")
                        .param("id", UUID.randomUUID()).param("c", council.councilId()).param("s", stall).param("t", trader)
                        .param("d", DAY.minusDays(i)).param("r", rule).update();
            }
        });
    }

    private Map<String, StallStatus> statusBy(String token) {
        MarketStatus s = api().get(token, base() + "/markets/" + market + "/map-status?date=" + DAY, MarketStatus.class).body();
        return s.stalls().stream().collect(java.util.stream.Collectors.toMap(StallStatus::code, x -> x));
    }

    @Test
    @DisplayName("each state and each levy band, with the edges: exactly 7 days quiet, 7 and 8 days owed")
    void statesAndBands() {
        seedCouncil();
        stall("MST-A-1", "VACANT", null, null);
        stall("MST-A-2", "SUSPENDED", "Suspended Trader", "+260977100002");
        UUID today = stall("MST-A-3", "ALLOCATED", "Today Trader", "+260977100003");
        UUID sixAgo = stall("MST-A-4", "ALLOCATED", "Six Days", "+260977100004");
        UUID sevenAgo = stall("MST-A-5", "ALLOCATED", "Seven Days", "+260977100005");
        UUID never = stall("MST-A-6", "ALLOCATED", "Never Traded", "+260977100006");
        deposit(today, DAY, 45_000);
        deposit(sixAgo, DAY.minusDays(6), 1_000);
        deposit(sevenAgo, DAY.minusDays(7), 1_000);
        unpaidLevy(sixAgo, 7);
        unpaidLevy(sevenAgo, 8);
        unpaidLevy(never, 1);

        Map<String, StallStatus> s = statusBy(council.adminToken());

        assertThat(s.get("MST-A-1").state()).isEqualTo("FREE");
        assertThat(s.get("MST-A-1").levy()).isEqualTo("NONE");
        assertThat(s.get("MST-A-2").state()).isEqualTo("SUSPENDED");
        assertThat(s.get("MST-A-3").state()).isEqualTo("ACTIVE");
        assertThat(s.get("MST-A-3").depositedTodayNgwee()).isEqualTo(45_000);
        assertThat(s.get("MST-A-3").levy()).isEqualTo("PAID_UP");
        assertThat(s.get("MST-A-4").state()).as("6 days ago").isEqualTo("ACTIVE");
        assertThat(s.get("MST-A-5").state()).as("exactly 7 days quiet").isEqualTo("QUIET");
        assertThat(s.get("MST-A-6").state()).isEqualTo("QUIET");
        assertThat(s.get("MST-A-4").levy()).as("7 days owed").isEqualTo("OWED_1_TO_7");
        assertThat(s.get("MST-A-5").levy()).as("8 days owed").isEqualTo("OWED_OVER_7");
        assertThat(s.get("MST-A-6").levy()).isEqualTo("OWED_1_TO_7");
        MarketStatus whole = api().get(council.adminToken(), base() + "/markets/" + market + "/map-status?date=" + DAY,
                MarketStatus.class).body();
        assertThat(whole.counts()).containsEntry("FREE", 1).containsEntry("SUSPENDED", 1).containsEntry("ACTIVE", 2)
                .containsEntry("QUIET", 2);
    }

    @Test
    @DisplayName("officers see phones masked; admins see them in full")
    void phonesByRole() {
        seedCouncil();
        stall("MST-B-1", "ALLOCATED", "Mary Phiri", "+260977123456");

        assertThat(statusBy(council.adminToken()).get("MST-B-1").traderPhone()).isEqualTo("+260977123456");
        String masked = statusBy(council.officerToken()).get("MST-B-1").traderPhone();
        assertThat(masked).isNotEqualTo("+260977123456").doesNotContain("123456");
    }

    @Test
    @DisplayName("search finds a trader by name, by 0977..., by 260977... and by +260977..., and a stall by code")
    void search() {
        seedCouncil();
        UUID s = stall("MST-C-7", "ALLOCATED", "Chanda Mwale", "+260977654321");
        stall("MST-C-8", "ALLOCATED", "Someone Else", "+260977111111");

        for (String q : List.of("chanda", "Mwale", "0977654321", "260977654321", "+260977654321")) {
            Hit[] hits = api().get(council.officerToken(), base() + "/search?q=" + q, Hit[].class).body();
            assertThat(hits).as(q).extracting(Hit::stallId).contains(s);
            assertThat(hits).as(q).extracting(Hit::traderName).doesNotContain("Someone Else");
        }
        assertThat(api().get(council.officerToken(), base() + "/search?q=mst-c-7", Hit[].class).body())
                .extracting(Hit::stallCode).containsExactly("MST-C-7");
        assertThat(api().get(council.officerToken(), base() + "/search?q=x", Hit[].class).body()).isEmpty();
        Fixture other = newCouncil();
        assertThat(api().get(other.adminToken(), "/api/v1/councils/" + other.councilId() + "/search?q=chanda", Hit[].class)
                .body()).isEmpty();
    }

    @Test
    @DisplayName("the stall panel: holder, last seven days, levy owed, history")
    void panel() {
        seedCouncil();
        UUID s = stall("MST-D-1", "ALLOCATED", "Panel Trader", "+260977222333");
        deposit(s, DAY, 30_000);
        deposit(s, DAY.minusDays(2), 10_000);
        unpaidLevy(s, 3);

        Panel p = api().get(council.officerToken(), base() + "/stalls/" + s + "/panel?date=" + DAY, Panel.class).body();

        assertThat(p.code()).isEqualTo("MST-D-1");
        assertThat(p.holder().name()).isEqualTo("Panel Trader");
        assertThat(p.holder().phone()).doesNotContain("222333");
        assertThat(p.lastSevenDays()).hasSize(7);
        assertThat(p.lastSevenDays().get(4).depositedNgwee()).isEqualTo(10_000);
        assertThat(p.depositedTodayNgwee()).isEqualTo(30_000);
        assertThat(p.unpaidLevyDays()).isEqualTo(3);
        assertThat(p.unpaidLevyNgwee()).isEqualTo(1_500);
        assertThat(p.history()).hasSize(1);
    }

    @Test
    @DisplayName("the status of a 5,000-stall market comes back in under 300 ms, from one query")
    void fiveThousandStalls() {
        seedCouncil();
        UUID c = council.councilId();
        // 5,000 stalls, 4,000 of them held, made in bulk.
        ownerJdbc().sql("""
                insert into stall (id, council_id, market_id, code, stall_class, status, qr_token)
                select gen_random_uuid(), :c, :m, 'MST-' || chr(65 + (g % 20)) || '-' || g, 'STANDARD',
                       case when g % 5 = 0 then 'VACANT' else 'ALLOCATED' end, md5(random()::text || g)
                from generate_series(1, 5000) g
                """).param("c", c).param("m", market).update();
        ownerJdbc().sql("""
                insert into trader (id, council_id, msisdn, display_name)
                select gen_random_uuid(), :c, '+26097' || lpad((3000000 + n)::text, 7, '0'), 'Trader ' || n
                from generate_series(1, 4000) n
                """).param("c", c).update();
        ownerJdbc().sql("""
                insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from)
                select gen_random_uuid(), :c, s.id, t.id, '2026-02-01T08:00:00+02:00'
                from (select id, row_number() over (order by code) r from stall where market_id = :m and status = 'ALLOCATED') s
                join (select id, row_number() over (order by display_name) r from trader where council_id = :c) t on t.r = s.r
                """).param("c", c).param("m", market).update();
        List<UUID> held = ownerJdbc().sql("select stall_id from stall_allocation where council_id = :c limit 300")
                .param("c", c).query(UUID.class).list();
        for (int i = 0; i < held.size(); i++) {
            deposit(held.get(i), DAY.minusDays(i % 10), 1_000 + i);
        }

        // A real market this size has table statistics (autovacuum keeps them); rows bulk-inserted a moment ago do not.
        // Without them the planner believes the market has one stall and joins by nested loop.
        for (String table : List.of("stall", "stall_allocation", "trader", "ledger_entry", "levy_charge", "deposit")) {
            ownerJdbc().sql("analyze " + table).update();
        }
        service.status(c, market, DAY, 7, true); // warm-up: first query plans, caches, JIT
        if (Boolean.getBoolean("msika.explain")) {
            tenant.inCouncil(c, () -> jdbc.sql("explain (analyze, buffers) " + MapStatusService.STATUS_SQL)
                    .param("c", c).param("m", market).param("day", DAY).param("start", DAY.minusDays(6))
                    .query(String.class).list().forEach(l -> System.out.println("PLAN " + l)));
        }
        long best = Long.MAX_VALUE;
        MapStatusService.MarketStatus result = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            result = service.status(c, market, DAY, 7, true);
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        System.out.println("MAP STATUS: 5,000 stalls in " + best + " ms (best of 3, after a warm-up) on "
                + Runtime.getRuntime().availableProcessors() + " cores, " + System.getProperty("os.name"));

        assertThat(result.stalls()).hasSize(5000);
        assertThat(result.counts().get(StallState.State.FREE)).isEqualTo(1000);
        Map<StallState.State, Integer> counts = result.counts();
        assertThat(Arrays.stream(StallState.State.values()).mapToInt(counts::get).sum()).isEqualTo(5000);
        assertThat(best).as("milliseconds for the status of 5,000 stalls").isLessThan(300);
    }
}
