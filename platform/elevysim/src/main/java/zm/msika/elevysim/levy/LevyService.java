package zm.msika.elevysim.levy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simulated eLevy (ARCHITECTURE.md section 6, card M-22): prepaid levy accounts, top-ups, the daily charge and
 * arrears.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>The daily charge takes one day's levy from the balance. What the balance cannot cover is added to arrears.
 *       An account is charged at most once per trading day, however often the charge runs.
 *   <li>A top-up pays off arrears first; anything left is added to the balance. The same idempotency key twice is
 *       one top-up, and the same key with a different amount is refused.
 *   <li>An account never has a balance and arrears at the same time (the database checks this too).
 * </ul>
 *
 * Each change locks the account row, so a top-up and a charge arriving together are applied one after the other.
 */
@Service
public class LevyService {

    public static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    /** An account as the API shows it. */
    public record Account(UUID id, String councilRef, String marketRef, String traderRef, long dailyLevyNgwee,
            long balanceNgwee, long arrearsNgwee, LocalDate lastChargedOn, LocalDate paidTo, Instant createdAt) {}

    /** One change to an account. */
    public record Movement(long id, String kind, long amountNgwee, long balanceAfter, long arrearsAfter,
            LocalDate chargeDate, String reference, Instant createdAt) {}

    /** What a daily charge run did. */
    public record ChargeRun(LocalDate date, int charged, int alreadyCharged, long totalNgwee) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    LevyService(JdbcClient jdbc, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public LocalDate today() {
        return clock.instant().atZone(LUSAKA).toLocalDate();
    }

    @Transactional
    public Account open(String councilRef, String marketRef, String traderRef, long dailyLevyNgwee) {
        require(councilRef, "councilRef");
        require(marketRef, "marketRef");
        require(traderRef, "traderRef");
        if (dailyLevyNgwee <= 0 || dailyLevyNgwee > 10_000_000) {
            throw new LevyExceptions.BadRequest("dailyLevyNgwee must be more than 0 and at most K100,000");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into levy_account (id, council_ref, market_ref, trader_ref, daily_levy_ngwee, created_at) "
                            + "values (:id, :c, :m, :t, :levy, :at)")
                    .param("id", id).param("c", councilRef.strip()).param("m", marketRef.strip())
                    .param("t", traderRef.strip()).param("levy", dailyLevyNgwee).param("at", utc(now()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new LevyExceptions.Conflict("this trader already has a levy account at this market");
        }
        return account(id);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.strip().length() > 100) {
            throw new LevyExceptions.BadRequest(name + " must be 1 to 100 characters");
        }
    }

    @Transactional(readOnly = true)
    public Account account(UUID id) {
        return find("id = :id", id).orElseThrow(() -> new LevyExceptions.NotFound("levy account not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Account> byTrader(String marketRef, String traderRef) {
        return jdbc.sql("select " + COLUMNS + " from levy_account where market_ref = :m and trader_ref = :t")
                .param("m", marketRef).param("t", traderRef)
                .query((rs, n) -> map(rs)).optional();
    }

    @Transactional(readOnly = true)
    public List<Account> list(String councilRef, int limit) {
        if (limit < 1 || limit > 5000) {
            throw new LevyExceptions.BadRequest("limit must be 1 to 5000");
        }
        return jdbc.sql("select " + COLUMNS + " from levy_account where (cast(:c as text) is null or council_ref = :c) "
                        + "order by created_at desc, id limit :limit")
                .param("c", councilRef).param("limit", limit)
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public List<Movement> movements(UUID id) {
        account(id);
        return jdbc.sql("select id, kind, amount_ngwee, balance_after, arrears_after, charge_date, reference, created_at "
                        + "from levy_movement where account_id = :id order by id")
                .param("id", id)
                .query((rs, n) -> new Movement(rs.getLong("id"), rs.getString("kind"), rs.getLong("amount_ngwee"),
                        rs.getLong("balance_after"), rs.getLong("arrears_after"),
                        rs.getObject("charge_date", LocalDate.class), rs.getString("reference"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Credits the account: arrears first, then balance. Msika calls this after the money has moved in the payments
     * system, with the payments transaction as the key, so a retry after a lost reply is harmless.
     */
    @Transactional
    public Account topUp(UUID id, String idempotencyKey, long amountNgwee, String reference) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new LevyExceptions.BadRequest("an Idempotency-Key of 1 to 200 characters is required");
        }
        if (amountNgwee <= 0) {
            throw new LevyExceptions.BadRequest("amountNgwee must be more than 0");
        }
        if (reference != null && reference.length() > 200) {
            throw new LevyExceptions.BadRequest("reference must be at most 200 characters");
        }
        Account a = lock(id);
        Optional<Long> earlier = jdbc.sql("select amount_ngwee from levy_movement where account_id = :id and idempotency_key = :k")
                .param("id", id).param("k", idempotencyKey).query(Long.class).optional();
        if (earlier.isPresent()) {
            if (earlier.get() != amountNgwee) {
                throw new LevyExceptions.Conflict("this Idempotency-Key was already used for a different amount");
            }
            return account(id);
        }
        long toArrears = Math.min(a.arrearsNgwee(), amountNgwee);
        long arrears = a.arrearsNgwee() - toArrears;
        long balance = Math.addExact(a.balanceNgwee(), amountNgwee - toArrears);
        write(a.id(), "TOP_UP", amountNgwee, balance, arrears, null, idempotencyKey, reference);
        return account(id);
    }

    /** Charges one account for one day, unless it was already charged that day. True if charged now. */
    @Transactional
    public boolean charge(UUID id, LocalDate date) {
        Account a = lock(id);
        boolean already = jdbc.sql("select exists (select 1 from levy_movement where account_id = :id and charge_date = :d)")
                .param("id", id).param("d", date).query(Boolean.class).single();
        if (already) {
            return false;
        }
        long levy = a.dailyLevyNgwee();
        long fromBalance = Math.min(a.balanceNgwee(), levy);
        long balance = a.balanceNgwee() - fromBalance;
        long arrears = Math.addExact(a.arrearsNgwee(), levy - fromBalance);
        write(a.id(), "CHARGE", levy, balance, arrears, date, null, null);
        jdbc.sql("update levy_account set last_charged_on = greatest(coalesce(last_charged_on, :d), :d) where id = :id")
                .param("d", date).param("id", id).update();
        return true;
    }

    /**
     * Charges every account opened on or before the date for that day. Safe to run again: accounts already charged
     * for the day are left alone. Each account is its own transaction, so one failure does not undo the rest.
     */
    public ChargeRun chargeAll(LocalDate date) {
        List<UUID> ids = jdbc.sql("select id from levy_account where (created_at at time zone 'Africa/Lusaka')::date <= :d "
                        + "order by id")
                .param("d", date).query(UUID.class).list();
        int charged = 0;
        int already = 0;
        long total = 0;
        for (UUID id : ids) {
            // Each account in its own transaction (a call to this.charge would skip the @Transactional proxy).
            if (Boolean.TRUE.equals(transactions.execute(status -> charge(id, date)))) {
                charged++;
                total += account(id).dailyLevyNgwee();
            } else {
                already++;
            }
        }
        return new ChargeRun(date, charged, already, total);
    }

    private Account lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from levy_account where id = :id for update")
                .param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new LevyExceptions.NotFound("levy account not found"));
    }

    private void write(UUID id, String kind, long amount, long balance, long arrears, LocalDate date, String key,
            String reference) {
        jdbc.sql("insert into levy_movement (account_id, kind, amount_ngwee, balance_after, arrears_after, charge_date, "
                        + "idempotency_key, reference, created_at) values (:id, :kind, :amount, :b, :a, :d, :k, :r, :at)")
                .param("id", id).param("kind", kind).param("amount", amount).param("b", balance).param("a", arrears)
                .param("d", date).param("k", key).param("r", reference).param("at", utc(now()))
                .update();
        jdbc.sql("update levy_account set balance_ngwee = :b, arrears_ngwee = :a where id = :id")
                .param("b", balance).param("a", arrears).param("id", id).update();
    }

    private Optional<Account> find(String where, Object id) {
        return jdbc.sql("select " + COLUMNS + " from levy_account where " + where)
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    private static final String COLUMNS = "id, council_ref, market_ref, trader_ref, daily_levy_ngwee, balance_ngwee, "
            + "arrears_ngwee, last_charged_on, created_at";

    private static Account map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long levy = rs.getLong("daily_levy_ngwee");
        long balance = rs.getLong("balance_ngwee");
        long arrears = rs.getLong("arrears_ngwee");
        LocalDate last = rs.getObject("last_charged_on", LocalDate.class);
        Instant created = rs.getObject("created_at", OffsetDateTime.class).toInstant();
        return new Account(rs.getObject("id", UUID.class), rs.getString("council_ref"), rs.getString("market_ref"),
                rs.getString("trader_ref"), levy, balance, arrears, last, paidTo(last, created, levy, balance, arrears),
                created);
    }

    /**
     * The last day the levy is paid for. With arrears, it is that many days before the last charge (rounded so a
     * part-paid day counts as unpaid); with a balance, the balance covers that many whole days after it.
     */
    static LocalDate paidTo(LocalDate lastCharged, Instant created, long levy, long balance, long arrears) {
        LocalDate base = lastCharged != null ? lastCharged : created.atZone(LUSAKA).toLocalDate().minusDays(1);
        if (arrears > 0) {
            return base.minusDays((arrears + levy - 1) / levy);
        }
        return base.plusDays(balance / levy);
    }
}
