package zm.msika.api.map;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.map.StallState;
import zm.msika.core.phone.PhoneNumber;

/**
 * What the map shows about each stall (card M-49, MARKET-MAP sections 7, 8.2, 8.3 and 11). States are worked out
 * from the register and activity every time, never stored. Phone numbers are shown in full only to people allowed to
 * change things; officers see them masked.
 */
@Service
public class MapStatusService {

    /** One stall on the map. */
    public record StallStatus(UUID stallId, String code, String stallClass, StallState.State state, StallState.LevyBand levy,
            int unpaidLevyDays, long depositedTodayNgwee, int openDisputes, LocalDate lastActivity, String traderName,
            String traderPhone) {}

    /** The whole market for a day, with the count in each state for the legend. */
    public record MarketStatus(LocalDate date, int quietDays, List<StallStatus> stalls, Map<StallState.State, Integer> counts,
            Map<StallState.LevyBand, Integer> levyCounts) {}

    public record Holder(UUID traderId, String name, String phone, Instant from, Instant to) {}

    public record DayAmount(LocalDate date, long depositedNgwee) {}

    /** Everything the stall panel shows. */
    public record Panel(UUID stallId, String code, String stallClass, String status, UUID marketId, String marketCode,
            String aisle, String side, Integer position, Holder holder, List<DayAmount> lastSevenDays,
            long depositedTodayNgwee, int unpaidLevyDays, long unpaidLevyNgwee, int openDisputes, List<Holder> history) {}

    /** One search hit: a trader and their stalls, or a stall. */
    public record Hit(UUID traderId, String traderName, String traderPhone, UUID stallId, String stallCode, UUID marketId) {}

    /** The status query; package-private so a test can print its plan. */
    static final String STATUS_SQL = """
                        with act as materialized (
                            select stall_id, max(business_date) as last_day,
                                   sum(amount_ngwee) filter (where kind = 'DEPOSIT' and business_date = :day) as today
                            from ledger_entry
                            where council_id = :c and business_date between :start and :day
                              and kind in ('DEPOSIT', 'MERCHANT_SALE')
                            group by stall_id
                        ), unpaid as materialized (
                            select stall_id, count(*) as n from levy_charge
                            where council_id = :c and settled_by_instruction_id is null and business_date <= :day
                            group by stall_id
                        ), disputes as materialized (
                            select stall_id, count(*) as n from deposit where council_id = :c and status = 'DISPUTED'
                            group by stall_id
                        )
                        select s.id, s.code, s.stall_class, s.status, t.display_name, t.msisdn, act.last_day,
                               coalesce(act.today, 0) as today, coalesce(unpaid.n, 0) as unpaid, coalesce(disputes.n, 0) as disputes
                        from stall s
                        left join stall_allocation a on a.stall_id = s.id and a.valid_to is null
                        left join trader t on t.id = a.trader_id
                        left join act on act.stall_id = s.id
                        left join unpaid on unpaid.stall_id = s.id
                        left join disputes on disputes.stall_id = s.id
                        where s.council_id = :c and s.market_id = :m
                        order by s.code
                        """;

    private final TenantContext tenant;
    private final JdbcClient jdbc;

    MapStatusService(TenantContext tenant, JdbcClient jdbc) {
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    private static String phone(String e164, boolean fullPhones) {
        if (e164 == null) {
            return null;
        }
        return fullPhones ? e164 : new PhoneNumber(e164).masked();
    }

    /**
     * One query for the whole market (MARKET-MAP section 11: a 5,000-stall market in under 300 ms). The three summaries
     * are {@code MATERIALIZED} so each is worked out once and joined by hash, whatever the planner believes about row
     * counts; without it, stale statistics led to a summary being recomputed for every stall (seconds, not
     * milliseconds).
     */
    public MarketStatus status(UUID councilId, UUID marketId, LocalDate day, int quietDays, boolean fullPhones) {
        LocalDate windowStart = day.minusDays(quietDays - 1L);
        List<StallStatus> stalls = tenant.inCouncil(councilId, () -> jdbc.sql(STATUS_SQL)
                .param("c", councilId).param("m", marketId).param("day", day).param("start", windowStart)
                .query((rs, n) -> {
                    String status = rs.getString("status");
                    LocalDate last = rs.getObject("last_day", LocalDate.class);
                    int unpaid = rs.getInt("unpaid");
                    boolean held = !"VACANT".equals(status) && rs.getString("display_name") != null;
                    return new StallStatus(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("stall_class"),
                            StallState.state(status, last, day, quietDays), StallState.levyBand(held, unpaid), unpaid,
                            rs.getLong("today"), rs.getInt("disputes"), last, rs.getString("display_name"),
                            phone(rs.getString("msisdn"), fullPhones));
                })
                .list());
        Map<StallState.State, Integer> counts = new EnumMap<>(StallState.State.class);
        Map<StallState.LevyBand, Integer> levy = new EnumMap<>(StallState.LevyBand.class);
        for (StallState.State s : StallState.State.values()) {
            counts.put(s, 0);
        }
        for (StallState.LevyBand b : StallState.LevyBand.values()) {
            levy.put(b, 0);
        }
        stalls.forEach(s -> {
            counts.merge(s.state(), 1, Integer::sum);
            levy.merge(s.levy(), 1, Integer::sum);
        });
        return new MarketStatus(day, quietDays, stalls, counts, levy);
    }

    public Panel panel(UUID councilId, UUID stallId, LocalDate day, boolean fullPhones) {
        return tenant.inCouncil(councilId, () -> {
            record Head(String code, String cls, String status, UUID marketId, String marketCode) {}
            Head h = jdbc.sql("select s.code, s.stall_class, s.status, s.market_id, m.code as mcode from stall s "
                            + "join market m on m.id = s.market_id where s.council_id = :c and s.id = :s")
                    .param("c", councilId).param("s", stallId)
                    .query((rs, n) -> new Head(rs.getString("code"), rs.getString("stall_class"), rs.getString("status"),
                            rs.getObject("market_id", UUID.class), rs.getString("mcode")))
                    .optional().orElseThrow(() -> new NotFoundException("no stall " + stallId));
            List<Holder> history = jdbc.sql("select t.id, t.display_name, t.msisdn, a.valid_from, a.valid_to from stall_allocation a "
                            + "join trader t on t.id = a.trader_id where a.council_id = :c and a.stall_id = :s order by a.valid_from desc")
                    .param("c", councilId).param("s", stallId)
                    .query((rs, n) -> new Holder(rs.getObject("id", UUID.class), rs.getString("display_name"),
                            phone(rs.getString("msisdn"), fullPhones), rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                            Optional.ofNullable(rs.getObject("valid_to", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null)))
                    .list();
            Holder holder = history.stream().filter(x -> x.to() == null).findFirst().orElse(null);
            record Address(String aisle, String side, Integer position) {}
            Address address = jdbc.sql("select f.aisle, f.side, f.position from stall_footprint f join market_plan p on p.id = f.plan_id "
                            + "where f.council_id = :c and f.stall_id = :s and p.status = 'PUBLISHED' limit 1")
                    .param("c", councilId).param("s", stallId)
                    .query((rs, n) -> new Address(rs.getString("aisle"), rs.getString("side"), rs.getObject("position", Integer.class)))
                    .optional().orElse(new Address(null, null, null));
            List<DayAmount> week = jdbc.sql("select d::date as day, coalesce((select sum(e.amount_ngwee) from ledger_entry e "
                            + "where e.council_id = :c and e.stall_id = :s and e.kind = 'DEPOSIT' and e.business_date = d::date), 0) as amount "
                            + "from generate_series(cast(:from as date), cast(:day as date), interval '1 day') d order by d")
                    .param("c", councilId).param("s", stallId).param("from", day.minusDays(6)).param("day", day)
                    .query((rs, n) -> new DayAmount(rs.getObject("day", LocalDate.class), rs.getLong("amount")))
                    .list();
            long[] levy = jdbc.sql("select count(*), coalesce(sum(amount_ngwee), 0) from levy_charge where council_id = :c "
                            + "and stall_id = :s and settled_by_instruction_id is null and business_date <= :day")
                    .param("c", councilId).param("s", stallId).param("day", day)
                    .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
            int disputes = jdbc.sql("select count(*) from deposit where council_id = :c and stall_id = :s and status = 'DISPUTED'")
                    .param("c", councilId).param("s", stallId).query(Integer.class).single();
            return new Panel(stallId, h.code(), h.cls(), h.status(), h.marketId(), h.marketCode(), address.aisle(),
                    address.side(), address.position(), holder, week, week.get(week.size() - 1).depositedNgwee(),
                    (int) levy[0], levy[1], disputes, history);
        });
    }

    /**
     * Who is where: a trader by name, or by phone in any form ({@code 0977...}, {@code 260977...}, {@code +260977...}),
     * or a stall by code. At most 20 hits.
     */
    public List<Hit> search(UUID councilId, String query, boolean fullPhones) {
        String q = query == null ? "" : query.strip();
        if (q.length() < 2) {
            return List.of();
        }
        String phone = null;
        if (q.replaceAll("[\\s+-]", "").matches("\\d{9,12}")) {
            try {
                phone = PhoneNumber.parse(q).e164();
            } catch (RuntimeException e) {
                phone = null;
            }
        }
        String byPhone = phone;
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return tenant.inCouncil(councilId, () -> jdbc.sql("""
                        select t.id as tid, t.display_name, t.msisdn, s.id as sid, s.code, s.market_id
                        from trader t
                        left join stall_allocation a on a.trader_id = t.id and a.valid_to is null
                        left join stall s on s.id = a.stall_id
                        where t.council_id = :c and (t.msisdn = :phone or t.display_name ilike :like)
                        union all
                        select t.id, t.display_name, t.msisdn, s.id, s.code, s.market_id
                        from stall s
                        left join stall_allocation a on a.stall_id = s.id and a.valid_to is null
                        left join trader t on t.id = a.trader_id
                        where s.council_id = :c and s.code ilike :like
                        order by 2 nulls last, 5
                        limit 20
                        """)
                .param("c", councilId).param("phone", byPhone).param("like", like)
                .query((rs, n) -> new Hit(rs.getObject("tid", UUID.class), rs.getString("display_name"),
                        phone(rs.getString("msisdn"), fullPhones), rs.getObject("sid", UUID.class), rs.getString("code"),
                        rs.getObject("market_id", UUID.class)))
                .list()
                .stream().distinct().toList());
    }
}
