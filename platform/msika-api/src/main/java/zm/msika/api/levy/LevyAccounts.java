package zm.msika.api.levy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.levy.LevyAuthorityPort;
import zm.msika.core.levy.LevyPlan;
import zm.msika.core.levy.LevyRule;

/**
 * Each trader's account at the levy authority, one per market (cards M-24, M-25). Accounts are opened when first
 * needed: by the first sweep, or by the trader asking {@code LEVY} or {@code BAL}. Opening calls the authority, so it
 * is never done inside a database transaction.
 */
@Component
public class LevyAccounts {

    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final LevyAuthorityPort authority;
    private final Clock clock;

    LevyAccounts(TenantContext tenant, JdbcClient jdbc, LevyAuthorityPort authority, Clock clock) {
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.authority = authority;
        this.clock = clock;
    }

    /** The linked account, if one was opened. Call inside a transaction for the council. */
    public Optional<String> linked(UUID councilId, UUID traderId, UUID marketId) {
        return jdbc.sql("select account_id from levy_account_link where council_id = :c and trader_id = :t and market_id = :m")
                .param("c", councilId).param("t", traderId).param("m", marketId).query(String.class).optional();
    }

    /** The daily levy in force today for a stall, if a rule covers it. Call inside a transaction for the council. */
    public Optional<Long> dailyLevy(UUID councilId, UUID stallId, LocalDate day) {
        record StallKind(UUID marketId, String stallClass) {}
        StallKind s = jdbc.sql("select market_id, stall_class from stall where council_id = :c and id = :s")
                .param("c", councilId).param("s", stallId)
                .query((rs, n) -> new StallKind(rs.getObject("market_id", UUID.class), rs.getString("stall_class")))
                .single();
        var rules = jdbc.sql("select id, market_id, stall_class, amount_ngwee, valid_from from levy_rule "
                        + "where council_id = :c and market_id = :m and stall_class = :cls")
                .param("c", councilId).param("m", s.marketId()).param("cls", s.stallClass())
                .query((rs, n) -> new LevyRule(rs.getObject("id", UUID.class), councilId, rs.getObject("market_id", UUID.class),
                        rs.getString("stall_class"), rs.getLong("amount_ngwee"), rs.getObject("valid_from", LocalDate.class)))
                .list();
        return LevyPlan.ruleFor(rules, s.marketId(), s.stallClass(), day).map(LevyRule::amountNgwee);
    }

    /**
     * The trader's account at the market, opening it at the authority first if needed. Outside any transaction.
     *
     * @throws zm.msika.core.levy.LevyAuthorityUnavailableException if the authority could not be reached
     */
    public String ensure(UUID councilId, UUID traderId, UUID marketId, long dailyLevyNgwee) {
        Optional<String> existing = tenant.inCouncil(councilId, () -> linked(councilId, traderId, marketId));
        if (existing.isPresent()) {
            return existing.get();
        }
        String opened = authority.openAccount(councilId.toString(), marketId.toString(), traderId.toString(), dailyLevyNgwee);
        tenant.inCouncil(councilId, () -> jdbc.sql("insert into levy_account_link (council_id, trader_id, market_id, "
                        + "account_id, created_at) values (:c, :t, :m, :a, :at) on conflict do nothing")
                .param("c", councilId).param("t", traderId).param("m", marketId).param("a", opened)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update());
        return opened;
    }
}
