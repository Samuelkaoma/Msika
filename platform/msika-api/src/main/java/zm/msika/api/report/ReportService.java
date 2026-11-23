package zm.msika.api.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import zm.msika.api.seal.SealKeys;
import zm.msika.api.seal.SealVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.common.InvalidInputException;
import zm.msika.ledger.EcdsaSignatureScheme;

/**
 * The figures the council dashboard shows (card M-27). Read only, one council at a time (row-level security), and
 * worked out from the records every time: nothing here is stored, so a figure can always be checked against the
 * ledger with a query.
 *
 * <p>Provenance stays apart (AGENTS.md rule 3): deposited cash, verified digital sales and declared sales are always
 * separate columns, never added together. A reversal counts against the kind of entry it reverses.
 */
@Service
public class ReportService {

    /** How many days one report may cover. */
    static final int MAX_DAYS = 92;

    public record MarketOccupancy(UUID marketId, String code, String name, int stalls, int vacant, int allocated,
            int suspended, int activeOnDay) {}

    public record DayCollections(LocalDate businessDate, UUID marketId, String marketCode, long depositedNgwee,
            long digitalNgwee, long declaredNgwee, long levyChargedNgwee, long levyPaidNgwee) {}

    public record Arrears(UUID traderId, String traderName, String stallCode, String marketCode, int unpaidDays,
            long unpaidNgwee, LocalDate oldestUnpaid) {}

    public record AgentActivity(UUID agentId, String name, boolean active, int confirmed, long confirmedNgwee, int pending,
            int failed, int cancelled, int disputed) {}

    public record ReconciliationDay(LocalDate businessDate, Instant ranAt, int paymentsCount, int msikaCount,
            int differenceCount) {}

    public record Seal(LocalDate businessDate, int entryCount, String merkleRoot, Instant sealedAt) {}

    public record SealCheck(boolean ok, int seals, int entries, List<String> problems) {}

    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final SealKeys keys;

    ReportService(TenantContext tenant, JdbcClient jdbc, SealKeys keys) {
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.keys = keys;
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new InvalidInputException("from and to are required, to on or after from, at most " + MAX_DAYS + " days");
        }
    }

    /** Stalls per market: vacant, allocated, suspended, and how many had any trade recorded on the day. */
    public List<MarketOccupancy> markets(UUID councilId, LocalDate day) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("""
                        select m.id, m.code, m.name,
                               count(s.id) as stalls,
                               count(s.id) filter (where s.status = 'VACANT') as vacant,
                               count(s.id) filter (where s.status = 'ALLOCATED') as allocated,
                               count(s.id) filter (where s.status = 'SUSPENDED') as suspended,
                               count(s.id) filter (where exists (
                                   select 1 from ledger_entry e where e.council_id = m.council_id and e.stall_id = s.id
                                     and e.business_date = :day
                                     and e.kind in ('DEPOSIT', 'MERCHANT_SALE', 'DECLARED_SALE'))) as active
                        from market m left join stall s on s.market_id = m.id and s.council_id = m.council_id
                        where m.council_id = :c
                        group by m.id, m.code, m.name order by m.code
                        """)
                .param("c", councilId).param("day", day)
                .query((rs, n) -> new MarketOccupancy(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getInt("stalls"), rs.getInt("vacant"), rs.getInt("allocated"),
                        rs.getInt("suspended"), rs.getInt("active")))
                .list());
    }

    /** Per business day and market: each provenance apart, and levy charged and paid. Days with nothing are left out. */
    public List<DayCollections> collections(UUID councilId, LocalDate from, LocalDate to) {
        range(from, to);
        return tenant.inCouncil(councilId, () -> jdbc.sql("""
                        with money as (
                            select e.business_date as d, s.market_id as m, coalesce(r.kind, e.kind) as k, e.amount_ngwee as a
                            from ledger_entry e
                            join stall s on s.id = e.stall_id
                            left join ledger_entry r on r.id = e.reverses_entry_id
                            where e.council_id = :c and e.business_date between :from and :to
                        ), charged as (
                            select l.business_date as d, s.market_id as m, sum(l.amount_ngwee) as a
                            from levy_charge l join stall s on s.id = l.stall_id
                            where l.council_id = :c and l.business_date between :from and :to
                            group by 1, 2
                        ), days as (
                            select d, m from money union select d, m from charged
                        )
                        select days.d, days.m, mk.code,
                               coalesce((select sum(a) from money x where x.d = days.d and x.m = days.m and x.k = 'DEPOSIT'), 0) dep,
                               coalesce((select sum(a) from money x where x.d = days.d and x.m = days.m and x.k = 'MERCHANT_SALE'), 0) dig,
                               coalesce((select sum(a) from money x where x.d = days.d and x.m = days.m and x.k = 'DECLARED_SALE'), 0) dec,
                               coalesce((select a from charged x where x.d = days.d and x.m = days.m), 0) chg,
                               coalesce((select sum(a) from money x where x.d = days.d and x.m = days.m and x.k = 'LEVY_PAYMENT'), 0) paid
                        from days join market mk on mk.id = days.m
                        order by days.d, mk.code
                        """)
                .param("c", councilId).param("from", from).param("to", to)
                .query((rs, n) -> new DayCollections(rs.getObject("d", LocalDate.class), rs.getObject("m", UUID.class),
                        rs.getString("code"), rs.getLong("dep"), rs.getLong("dig"), rs.getLong("dec"), rs.getLong("chg"),
                        rs.getLong("paid")))
                .list());
    }

    /** Every trader with unpaid levy, most owed first. */
    public List<Arrears> arrears(UUID councilId) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("""
                        select t.id, t.display_name, s.code as stall, m.code as market,
                               count(*) as days, sum(l.amount_ngwee) as owed, min(l.business_date) as oldest
                        from levy_charge l
                        join trader t on t.id = l.trader_id
                        join stall s on s.id = l.stall_id
                        join market m on m.id = s.market_id
                        where l.council_id = :c and l.settled_by_instruction_id is null
                        group by t.id, t.display_name, s.code, m.code
                        order by owed desc, stall
                        """)
                .param("c", councilId)
                .query((rs, n) -> new Arrears(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("stall"), rs.getString("market"), rs.getInt("days"), rs.getLong("owed"),
                        rs.getObject("oldest", LocalDate.class)))
                .list());
    }

    /** Each agent's deposits over the days, by outcome. */
    public List<AgentActivity> agents(UUID councilId, LocalDate from, LocalDate to) {
        range(from, to);
        return tenant.inCouncil(councilId, () -> jdbc.sql("""
                        select a.id, a.display_name, a.active,
                               count(d.id) filter (where d.status = 'CONFIRMED') as confirmed,
                               coalesce(sum(d.amount_ngwee) filter (where d.status = 'CONFIRMED'), 0) as confirmed_ngwee,
                               count(d.id) filter (where d.status = 'PENDING') as pending,
                               count(d.id) filter (where d.status = 'FAILED') as failed,
                               count(d.id) filter (where d.status = 'CANCELLED') as cancelled,
                               count(d.id) filter (where d.status = 'DISPUTED') as disputed
                        from agent a
                        left join deposit d on d.agent_id = a.id and d.business_date between :from and :to
                        where a.council_id = :c
                        group by a.id, a.display_name, a.active
                        order by disputed desc, a.display_name
                        """)
                .param("c", councilId).param("from", from).param("to", to)
                .query((rs, n) -> new AgentActivity(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getBoolean("active"), rs.getInt("confirmed"), rs.getLong("confirmed_ngwee"), rs.getInt("pending"),
                        rs.getInt("failed"), rs.getInt("cancelled"), rs.getInt("disputed")))
                .list());
    }

    /** The nightly reconciliation's runs over the days, with how many differences each found (M-16). */
    public List<ReconciliationDay> reconciliation(UUID councilId, LocalDate from, LocalDate to) {
        range(from, to);
        return tenant.inCouncil(councilId, () -> jdbc.sql("select business_date, ran_at, payments_count, msika_count, "
                        + "difference_count from reconciliation_run where council_id = :c "
                        + "and business_date between :from and :to order by business_date desc")
                .param("c", councilId).param("from", from).param("to", to)
                .query((rs, n) -> new ReconciliationDay(rs.getObject("business_date", LocalDate.class),
                        rs.getObject("ran_at", OffsetDateTime.class).toInstant(), rs.getInt("payments_count"),
                        rs.getInt("msika_count"), rs.getInt("difference_count")))
                .list());
    }

    /** The sealed days, newest first. */
    public List<Seal> seals(UUID councilId, int limit) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("select business_date, entry_count, merkle_root, sealed_at "
                        + "from daily_seal where council_id = :c order by business_date desc limit :limit")
                .param("c", councilId).param("limit", Math.max(1, Math.min(limit, 400)))
                .query((rs, n) -> new Seal(rs.getObject("business_date", LocalDate.class), rs.getInt("entry_count"),
                        HexFormat.of().formatHex(rs.getBytes("merkle_root")),
                        rs.getObject("sealed_at", OffsetDateTime.class).toInstant()))
                .list());
    }

    /**
     * Checks every sealed day of the council against its ledger: the entries, the Merkle roots, the chain from day to
     * day, and the signatures, exactly as {@code scripts/verify-seal} does.
     */
    public SealCheck verifySeals(UUID councilId) {
        SealVerifier.Report r = tenant.inCouncil(councilId,
                () -> new SealVerifier(jdbc, new EcdsaSignatureScheme()).verify(keys.publicKey(), councilId));
        return new SealCheck(r.ok(), r.seals(), r.entries(), r.problems());
    }
}
