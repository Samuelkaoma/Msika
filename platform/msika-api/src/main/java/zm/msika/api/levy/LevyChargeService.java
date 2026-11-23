package zm.msika.api.levy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.levy.LevyPlan;
import zm.msika.core.levy.LevyRule;

/**
 * Levy rules and the daily levy charge (card M-23). Every method runs as one council (row-level security).
 */
@Service
public class LevyChargeService {

    /** What a charge run did. */
    public record Run(LocalDate date, int charged, int alreadyCharged, long totalNgwee, List<String> stallsWithoutRule) {}

    /** One stall's charge for a day. */
    public record ChargeRow(UUID stallId, String stallCode, UUID traderId, LocalDate businessDate, long amountNgwee,
            UUID ruleId, UUID settledByInstructionId) {}

    private final TenantContext tenant;
    private final CouncilRepository councils;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Clock clock;

    LevyChargeService(TenantContext tenant, CouncilRepository councils, JdbcClient jdbc, AuditLog audit, Clock clock) {
        this.tenant = tenant;
        this.councils = councils;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    public LocalDate today(UUID councilId) {
        Council c = councils.find(councilId).orElseThrow(() -> new NotFoundException("no council " + councilId));
        return clock.instant().atZone(c.timezone()).toLocalDate();
    }

    public LevyRule addRule(UUID councilId, UUID marketId, String stallClass, long amountNgwee, LocalDate validFrom,
            String actor) {
        String cls = stallClass == null ? "" : stallClass.strip().toUpperCase(java.util.Locale.ROOT);
        if (!cls.matches("[A-Z0-9_]{1,20}")) {
            throw new InvalidInputException("stallClass must be 1 to 20 capital letters, digits or _, like STANDARD");
        }
        if (amountNgwee <= 0 || amountNgwee > 10_000_000) {
            throw new InvalidInputException("amountNgwee must be more than 0 and at most K100,000");
        }
        if (validFrom == null) {
            throw new InvalidInputException("validFrom is required, like 2026-12-01");
        }
        LevyRule rule = new LevyRule(UUID.randomUUID(), councilId, marketId, cls, amountNgwee, validFrom);
        tenant.inCouncil(councilId, () -> {
            boolean market = jdbc.sql("select exists (select 1 from market where council_id = :c and id = :m)")
                    .param("c", councilId).param("m", marketId).query(Boolean.class).single();
            if (!market) {
                throw new NotFoundException("no market " + marketId + " in this council");
            }
            int added = jdbc.sql("insert into levy_rule (id, council_id, market_id, stall_class, amount_ngwee, valid_from, "
                            + "created_at, created_by) values (:id, :c, :m, :cls, :amount, :from, :at, :by) "
                            + "on conflict (council_id, market_id, stall_class, valid_from) do nothing")
                    .param("id", rule.id()).param("c", councilId).param("m", marketId).param("cls", cls)
                    .param("amount", amountNgwee).param("from", validFrom).param("at", utc(clock.instant()))
                    .param("by", actor).update();
            if (added == 0) {
                throw new ConflictException("a rule for " + cls + " starting " + validFrom + " already exists");
            }
            audit.record(councilId, actor, "levy.rule.add", cls + " " + amountNgwee + " ngwee from " + validFrom);
        });
        return rule;
    }

    public List<LevyRule> rules(UUID councilId, UUID marketId) {
        return tenant.inCouncil(councilId, () -> rulesIn(councilId, marketId));
    }

    private List<LevyRule> rulesIn(UUID councilId, UUID marketIdOrNull) {
        return jdbc.sql("select id, market_id, stall_class, amount_ngwee, valid_from from levy_rule where council_id = :c "
                        + "and (cast(:m as uuid) is null or market_id = :m) order by market_id, stall_class, valid_from")
                .param("c", councilId).param("m", marketIdOrNull)
                .query((rs, n) -> new LevyRule(rs.getObject("id", UUID.class), councilId,
                        rs.getObject("market_id", UUID.class), rs.getString("stall_class"), rs.getLong("amount_ngwee"),
                        rs.getObject("valid_from", LocalDate.class)))
                .list();
    }

    /**
     * Charges the day's levy to every stall that was held when the day began. Safe to run again: a stall already
     * charged for the day is left alone. Stalls with no rule are reported, not charged.
     */
    public Run charge(UUID councilId, LocalDate day, String actor) {
        Council council = councils.find(councilId).orElseThrow(() -> new NotFoundException("no council " + councilId));
        OffsetDateTime start = day.atStartOfDay(council.timezone()).toOffsetDateTime();
        return tenant.inCouncil(councilId, () -> {
            List<LevyPlan.HeldStall> held = jdbc.sql("select s.id, s.market_id, s.stall_class, a.trader_id "
                            + "from stall_allocation a join stall s on s.id = a.stall_id "
                            + "where a.council_id = :c and a.valid_from <= :start and (a.valid_to is null or a.valid_to > :start) "
                            + "and s.status <> 'SUSPENDED'")
                    .param("c", councilId).param("start", start)
                    .query((rs, n) -> new LevyPlan.HeldStall(rs.getObject("id", UUID.class),
                            rs.getObject("market_id", UUID.class), rs.getString("stall_class"),
                            rs.getObject("trader_id", UUID.class)))
                    .list();
            LevyPlan.Plan plan = LevyPlan.plan(day, held, rulesIn(councilId, null));
            int charged = 0;
            long total = 0;
            Instant now = clock.instant();
            for (LevyPlan.Charge c : plan.charges()) {
                int added = jdbc.sql("insert into levy_charge (id, council_id, stall_id, trader_id, business_date, amount_ngwee, "
                                + "rule_id, created_at) values (:id, :c, :s, :t, :d, :amount, :rule, :at) "
                                + "on conflict (council_id, stall_id, business_date) do nothing")
                        .param("id", UUID.randomUUID()).param("c", councilId).param("s", c.stallId())
                        .param("t", c.traderId()).param("d", day).param("amount", c.amountNgwee())
                        .param("rule", c.ruleId()).param("at", utc(now)).update();
                charged += added;
                total += added * c.amountNgwee();
            }
            List<String> without = plan.withoutRule().isEmpty() ? List.of() : jdbc.sql(
                            "select code from stall where council_id = :c and id in (:ids) order by code")
                    .param("c", councilId).param("ids", plan.withoutRule().stream().map(LevyPlan.HeldStall::stallId).toList())
                    .query(String.class).list();
            if (charged > 0) {
                audit.record(councilId, actor, "levy.charge", day + ": " + charged + " stall(s), " + total + " ngwee");
            }
            return new Run(day, charged, plan.charges().size() - charged, total, without);
        });
    }

    public List<ChargeRow> charges(UUID councilId, LocalDate day) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("select l.stall_id, s.code, l.trader_id, l.business_date, "
                        + "l.amount_ngwee, l.rule_id, l.settled_by_instruction_id from levy_charge l "
                        + "join stall s on s.id = l.stall_id where l.council_id = :c and l.business_date = :d order by s.code")
                .param("c", councilId).param("d", day)
                .query((rs, n) -> new ChargeRow(rs.getObject("stall_id", UUID.class), rs.getString("code"),
                        rs.getObject("trader_id", UUID.class), rs.getObject("business_date", LocalDate.class),
                        rs.getLong("amount_ngwee"), rs.getObject("rule_id", UUID.class),
                        rs.getObject("settled_by_instruction_id", UUID.class)))
                .list());
    }
}
