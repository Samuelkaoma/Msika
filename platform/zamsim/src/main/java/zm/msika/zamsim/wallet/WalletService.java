package zm.msika.zamsim.wallet;

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
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.zamsim.callback.CallbackService;
import zm.msika.zamsim.fault.FaultService;
import zm.msika.zamsim.wallet.Wallets.StatementLine;
import zm.msika.zamsim.wallet.Wallets.Transaction;
import zm.msika.zamsim.wallet.Wallets.TransactionKind;
import zm.msika.zamsim.wallet.Wallets.TransactionStatus;
import zm.msika.zamsim.wallet.Wallets.Wallet;
import zm.msika.zamsim.wallet.Wallets.WalletType;
import zm.msika.zamsim.wallet.ZamsimExceptions.BadRequest;
import zm.msika.zamsim.wallet.ZamsimExceptions.Conflict;
import zm.msika.zamsim.wallet.ZamsimExceptions.NotFound;

/**
 * Simulated Zamtel Money: wallets and money movements, each as one database transaction.
 *
 * <p>Every movement is done the same careful way:
 *
 * <ol>
 *   <li>the idempotency key is claimed first, so a repeat returns the first result and a race between two
 *       requests with the same key has one winner;
 *   <li>both wallets are locked, in id order so two transfers in opposite directions cannot deadlock;
 *   <li>if the paying wallet cannot afford it, or the fault settings say to fail it, the transaction is
 *       recorded as FAILED and nothing moves;
 *   <li>otherwise both balances change and two ledger lines are written that add up to zero;
 *   <li>a callback is queued in the same database transaction, so it exists exactly when the payment
 *       settled.
 * </ol>
 *
 * <p>A request-to-pay is a payment that waits: it is created PENDING and settles later, through the same
 * steps, when the customer approves it (or fails when they reject it or it expires).
 *
 * <p>The database enforces the rules a second time (no negative balance, balanced ledger lines,
 * append-only ledger), so a bug here cannot create or destroy money.
 */
@Service
@Transactional
public class WalletService {

    private static final Pattern MSISDN = Pattern.compile("\\+260[79][0-9]{8}");
    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    static final String SIMULATED_FAILURE = "SIMULATED_FAILURE";
    static final String REJECTED = "REJECTED";
    static final String EXPIRED = "EXPIRED";

    /** A request-to-pay lasts this long unless the request says otherwise. */
    public static final int DEFAULT_REQUEST_TTL_SECONDS = 300;

    private static final String TRANSACTION_COLUMNS =
            "id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, reference, failure_reason, "
                    + "created_at, completed_at, expires_at";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final FaultService faults;
    private final CallbackService callbacks;

    WalletService(JdbcClient jdbc, Clock clock, FaultService faults, CallbackService callbacks) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.faults = faults;
        this.callbacks = callbacks;
    }

    // ----------------------------------------------------------------- wallets

    public Wallet createWallet(WalletType type, String name, String msisdn) {
        if (type == null || type == WalletType.ISSUER) {
            throw new BadRequest("type must be one of TRADER, AGENT, MERCHANT, COUNCIL, LENDER, SUPPLIER");
        }
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new BadRequest("name is required, at most 100 characters");
        }
        if (msisdn != null && !MSISDN.matcher(msisdn).matches()) {
            throw new BadRequest("msisdn must be like +260977000001");
        }
        UUID id = UUID.randomUUID();
        Instant now = now();
        try {
            jdbc.sql("insert into wallet (id, type, name, msisdn, balance_ngwee, created_at) "
                            + "values (:id, :type, :name, :msisdn, 0, :at)")
                    .param("id", id).param("type", type.name()).param("name", name.trim())
                    .param("msisdn", msisdn).param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new Conflict("a wallet with this msisdn already exists");
        }
        return new Wallet(id, type, name.trim(), msisdn, 0, now);
    }

    @Transactional(readOnly = true)
    public Wallet wallet(UUID id) {
        return findWallet(id).orElseThrow(() -> new NotFound("wallet not found"));
    }

    /** Every wallet, newest first, for the sim console. */
    @Transactional(readOnly = true)
    public List<Wallet> wallets(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new BadRequest("limit must be 1 to 1000");
        }
        return jdbc.sql("select id, type, name, msisdn, balance_ngwee, created_at from wallet "
                        + "where id <> :issuer order by created_at desc, id limit :limit")
                .param("issuer", Wallets.ISSUER_ID).param("limit", limit)
                .query((rs, row) -> mapWallet(rs))
                .list();
    }

    private Optional<Wallet> findWallet(UUID id) {
        return jdbc.sql("select id, type, name, msisdn, balance_ngwee, created_at from wallet where id = :id")
                .param("id", id)
                .query((rs, row) -> mapWallet(rs))
                .optional();
    }

    private static Wallet mapWallet(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Wallet(
                rs.getObject("id", UUID.class),
                WalletType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("msisdn"),
                rs.getLong("balance_ngwee"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    // -------------------------------------------------------------- movements

    /** An agent puts a customer's cash into the customer's wallet, out of the agent's float. */
    public Transaction cashIn(String key, UUID agentWalletId, UUID customerWalletId, long amount, String reference) {
        return move(key, TransactionKind.CASH_IN, agentWalletId, customerWalletId, amount, reference);
    }

    /** Wallet to wallet. */
    public Transaction transfer(String key, UUID from, UUID to, long amount, String reference) {
        return move(key, TransactionKind.TRANSFER, from, to, amount, reference);
    }

    /** Money enters the system (an agent buys float, a supplier is seeded): ISSUER to the wallet. */
    public Transaction fund(String key, UUID wallet, long amount, String reference) {
        return move(key, TransactionKind.FUND, Wallets.ISSUER_ID, wallet, amount, reference);
    }

    /** Money leaves the system: the wallet to ISSUER. */
    public Transaction withdraw(String key, UUID wallet, long amount, String reference) {
        return move(key, TransactionKind.WITHDRAW, wallet, Wallets.ISSUER_ID, amount, reference);
    }

    /**
     * A merchant asks a customer to pay. The request waits PENDING until {@link #approve} or
     * {@link #reject}, or until it expires. Nothing moves yet.
     *
     * @param ttlSeconds how long it stays open, or null for {@value #DEFAULT_REQUEST_TTL_SECONDS} seconds
     */
    public Transaction requestToPay(
            String key, UUID merchantWalletId, UUID customerWalletId, long amount, String reference, Integer ttlSeconds) {
        int ttl = ttlSeconds == null ? DEFAULT_REQUEST_TTL_SECONDS : ttlSeconds;
        if (ttl < 1 || ttl > 86_400) {
            throw new BadRequest("ttlSeconds must be 1 to 86400");
        }
        return move(key, TransactionKind.REQUEST_TO_PAY, customerWalletId, merchantWalletId, amount, reference, ttl);
    }

    private Transaction move(String key, TransactionKind kind, UUID from, UUID to, long amount, String reference) {
        return move(key, kind, from, to, amount, reference, 0);
    }

    private Transaction move(
            String key, TransactionKind kind, UUID from, UUID to, long amount, String reference, int ttlSeconds) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new BadRequest("an Idempotency-Key of 1 to 200 characters is required");
        }
        if (from == null || to == null) {
            throw new BadRequest("both wallets are required");
        }
        if (amount <= 0) {
            throw new BadRequest("amountNgwee must be greater than zero");
        }
        if (from.equals(to)) {
            throw new BadRequest("the two wallets must be different");
        }
        if (reference != null && reference.length() > 200) {
            throw new BadRequest("reference is at most 200 characters");
        }

        String fingerprint = kind + "|" + from + "|" + to + "|" + amount + "|" + (reference == null ? "" : reference)
                + "|" + ttlSeconds;
        // Claim the key. If another request holds it, Postgres waits here until that request commits.
        boolean first = jdbc.sql("insert into idempotency_record (idempotency_key, fingerprint, created_at) "
                        + "values (:key, :fp, :at) on conflict do nothing returning idempotency_key")
                .param("key", key).param("fp", fingerprint)
                .param("at", OffsetDateTime.ofInstant(now(), ZoneOffset.UTC))
                .query(String.class).optional().isPresent();
        if (!first) {
            return repeat(key, fingerprint);
        }

        Transaction transaction = kind == TransactionKind.REQUEST_TO_PAY
                ? openRequest(from, to, amount, reference, ttlSeconds)
                : process(kind, from, to, amount, reference);
        jdbc.sql("update idempotency_record set transaction_id = :tx where idempotency_key = :key")
                .param("tx", transaction.id()).param("key", key).update();
        return transaction;
    }

    /** The same key again: the first answer, or a conflict if it is a different request. */
    private Transaction repeat(String key, String fingerprint) {
        var record = jdbc.sql("select fingerprint, transaction_id from idempotency_record where idempotency_key = :key")
                .param("key", key)
                .query((rs, row) -> new String[] {rs.getString("fingerprint"), rs.getString("transaction_id")})
                .single();
        if (!record[0].equals(fingerprint)) {
            throw new Conflict("the Idempotency-Key " + key + " was already used for a different request");
        }
        return transaction(UUID.fromString(record[1]));
    }

    private List<Wallet> lockWallets(UUID a, UUID b) {
        // Lock both wallets, lower id first, so opposite transfers cannot deadlock.
        return jdbc.sql("select id, type, name, msisdn, balance_ngwee, created_at from wallet "
                        + "where id in (:a, :b) order by id for update")
                .param("a", a).param("b", b)
                .query((rs, row) -> mapWallet(rs))
                .list();
    }

    private static Wallet pick(List<Wallet> locked, UUID id) {
        return locked.stream().filter(w -> w.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFound("wallet " + id + " not found"));
    }

    private Transaction process(TransactionKind kind, UUID fromId, UUID toId, long amount, String reference) {
        List<Wallet> locked = lockWallets(fromId, toId);
        Wallet from = pick(locked, fromId);
        Wallet to = pick(locked, toId);
        checkKind(kind, from, to);

        UUID id = UUID.randomUUID();
        Instant now = now();
        return settle(id, kind, from, to, amount, reference, now, now, null);
    }

    /**
     * The one place a payment reaches its final state: either it fails (not enough money, or the fault
     * settings say so) and nothing moves, or both balances change and two balancing lines are written.
     * Queues the callback in the same database transaction. The caller has already locked both wallets.
     */
    private Transaction settle(UUID id, TransactionKind kind, Wallet from, Wallet to, long amount, String reference,
            Instant createdAt, Instant completedAt, Instant expiresAt) {
        UUID fromId = from.id();
        UUID toId = to.id();
        boolean injectFailure = kind != TransactionKind.FUND && kind != TransactionKind.WITHDRAW
                && faults.happens(faults.settings().failureRate());
        boolean canAfford = from.type() == WalletType.ISSUER || from.balanceNgwee() >= amount;

        if (injectFailure || !canAfford) {
            String reason = injectFailure ? SIMULATED_FAILURE : INSUFFICIENT_FUNDS;
            return conclude(id, kind, TransactionStatus.FAILED, fromId, toId, amount, reference, reason, createdAt,
                    completedAt, expiresAt);
        }

        long fromAfter = Math.subtractExact(from.balanceNgwee(), amount);
        long toAfter = Math.addExact(to.balanceNgwee(), amount);
        Transaction done = conclude(id, kind, TransactionStatus.SUCCEEDED, fromId, toId, amount, reference, null,
                createdAt, completedAt, expiresAt);
        OffsetDateTime at = OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC);
        jdbc.sql("update wallet set balance_ngwee = :b where id = :id").param("b", fromAfter).param("id", fromId).update();
        jdbc.sql("update wallet set balance_ngwee = :b where id = :id").param("b", toAfter).param("id", toId).update();
        insertLine(id, fromId, -amount, fromAfter, at);
        insertLine(id, toId, amount, toAfter, at);
        return done;
    }

    /**
     * Records the final state of a transaction (inserting it if it is new, updating it if it was PENDING)
     * and queues the callback if this kind is reported by callback.
     */
    private Transaction conclude(UUID id, TransactionKind kind, TransactionStatus status, UUID fromId, UUID toId,
            long amount, String reference, String failureReason, Instant createdAt, Instant completedAt,
            Instant expiresAt) {
        OffsetDateTime completed = OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC);
        if (kind == TransactionKind.REQUEST_TO_PAY) {
            jdbc.sql("update transaction set status = :status, failure_reason = :failure, completed_at = :at "
                            + "where id = :id")
                    .param("status", status.name()).param("failure", failureReason).param("at", completed)
                    .param("id", id).update();
        } else {
            jdbc.sql("insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, "
                            + "reference, failure_reason, created_at, completed_at) "
                            + "values (:id, :kind, :status, :from, :to, :amount, :ref, :failure, :created, :completed)")
                    .param("id", id).param("kind", kind.name()).param("status", status.name())
                    .param("from", fromId).param("to", toId).param("amount", amount).param("ref", reference)
                    .param("failure", failureReason)
                    .param("created", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)).param("completed", completed)
                    .update();
        }
        Transaction transaction = new Transaction(id, kind, status, fromId, toId, amount, reference, failureReason,
                createdAt, completedAt, expiresAt);
        if (kind.reportedByCallback()) {
            callbacks.onCompleted(transaction);
        }
        return transaction;
    }

    /** The kinds only make sense between certain wallet types. A wrong pairing is a bad request, not a failed payment. */
    private static void checkKind(TransactionKind kind, Wallet from, Wallet to) {
        switch (kind) {
            case CASH_IN -> {
                if (from.type() != WalletType.AGENT) {
                    throw new BadRequest("cash-in must come from an AGENT wallet");
                }
                if (to.type() == WalletType.ISSUER || to.type() == WalletType.AGENT) {
                    throw new BadRequest("cash-in must go to a customer wallet, not an agent or the issuer");
                }
            }
            case TRANSFER -> {
                if (from.type() == WalletType.ISSUER || to.type() == WalletType.ISSUER) {
                    throw new BadRequest("a transfer cannot involve the issuer; use fund or withdraw");
                }
            }
            case FUND -> {
                if (to.type() == WalletType.ISSUER) {
                    throw new BadRequest("cannot fund the issuer");
                }
            }
            case WITHDRAW -> {
                if (from.type() == WalletType.ISSUER) {
                    throw new BadRequest("cannot withdraw from the issuer");
                }
            }
            case REQUEST_TO_PAY -> {
                if (to.type() != WalletType.MERCHANT) {
                    throw new BadRequest("a request-to-pay must be made by a MERCHANT wallet");
                }
                if (from.type() == WalletType.ISSUER || from.type() == WalletType.AGENT) {
                    throw new BadRequest("a request-to-pay must ask a customer, not an agent or the issuer");
                }
            }
        }
    }

    private void insertLine(UUID transaction, UUID wallet, long amount, long balanceAfter, OffsetDateTime at) {
        jdbc.sql("insert into ledger_line (transaction_id, wallet_id, amount_ngwee, balance_after_ngwee, created_at) "
                        + "values (:tx, :wallet, :amount, :after, :at)")
                .param("tx", transaction).param("wallet", wallet).param("amount", amount)
                .param("after", balanceAfter).param("at", at)
                .update();
    }

    // ------------------------------------------------------------- request-to-pay

    private Transaction openRequest(UUID customerId, UUID merchantId, long amount, String reference, int ttlSeconds) {
        List<Wallet> locked = lockWallets(customerId, merchantId);
        checkKind(TransactionKind.REQUEST_TO_PAY, pick(locked, customerId), pick(locked, merchantId));
        UUID id = UUID.randomUUID();
        Instant now = now();
        Instant expires = now.plusSeconds(ttlSeconds);
        jdbc.sql("insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, reference, "
                        + "created_at, expires_at) values (:id, 'REQUEST_TO_PAY', 'PENDING', :from, :to, :amount, :ref, "
                        + ":created, :expires)")
                .param("id", id).param("from", customerId).param("to", merchantId).param("amount", amount)
                .param("ref", reference).param("created", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("expires", OffsetDateTime.ofInstant(expires, ZoneOffset.UTC)).update();
        return new Transaction(id, TransactionKind.REQUEST_TO_PAY, TransactionStatus.PENDING, customerId, merchantId,
                amount, reference, null, now, null, expires);
    }

    /**
     * The customer approves a pending request: the payment is made now (or fails for lack of money).
     * Approving twice is harmless and returns the same result. A request that was rejected, or has
     * expired, cannot be approved.
     */
    public Transaction approve(UUID id) {
        Transaction pending = lockPending(id);
        if (pending.status() != TransactionStatus.PENDING) {
            if (pending.status() == TransactionStatus.SUCCEEDED || SIMULATED_FAILURE.equals(pending.failureReason())
                    || INSUFFICIENT_FUNDS.equals(pending.failureReason())) {
                return pending; // already approved (and settled, one way or the other)
            }
            throw new Conflict("this request is already " + pending.failureReason());
        }
        if (!now().isBefore(pending.expiresAt())) {
            return resolve(pending, EXPIRED);
        }
        List<Wallet> locked = lockWallets(pending.fromWalletId(), pending.toWalletId());
        return settle(pending.id(), TransactionKind.REQUEST_TO_PAY, pick(locked, pending.fromWalletId()),
                pick(locked, pending.toWalletId()), pending.amountNgwee(), pending.reference(), pending.createdAt(),
                now(), pending.expiresAt());
    }

    /** The customer turns a pending request down. Rejecting twice is harmless. */
    public Transaction reject(UUID id) {
        Transaction pending = lockPending(id);
        if (pending.status() == TransactionStatus.PENDING) {
            return resolve(pending, REJECTED);
        }
        if (REJECTED.equals(pending.failureReason())) {
            return pending;
        }
        throw new Conflict("this request is already " + pending.status());
    }

    /**
     * Fails every pending request whose time has run out, reporting each by callback. Runs on a schedule;
     * safe to call at any time.
     *
     * @return how many were expired
     */
    public int expireOverdue() {
        List<UUID> overdue = jdbc.sql("select id from transaction where status = 'PENDING' and expires_at <= :now "
                        + "order by expires_at, id limit 200")
                .param("now", OffsetDateTime.ofInstant(now(), ZoneOffset.UTC))
                .query(UUID.class).list();
        int expired = 0;
        for (UUID id : overdue) {
            Transaction pending = lockPending(id);
            if (pending.status() == TransactionStatus.PENDING) {
                resolve(pending, EXPIRED);
                expired++;
            }
        }
        return expired;
    }

    /** A request-to-pay that is waiting, for the console to show the customer. */
    @Transactional(readOnly = true)
    public List<Transaction> pendingRequests(UUID customerWalletId) {
        wallet(customerWalletId);
        return jdbc.sql("select " + TRANSACTION_COLUMNS + " from transaction where kind = 'REQUEST_TO_PAY' "
                        + "and status = 'PENDING' and from_wallet_id = :wallet order by created_at, id")
                .param("wallet", customerWalletId)
                .query((rs, row) -> mapTransaction(rs))
                .list();
    }

    private Transaction lockPending(UUID id) {
        return jdbc.sql("select " + TRANSACTION_COLUMNS + " from transaction where id = :id and kind = 'REQUEST_TO_PAY' "
                        + "for update")
                .param("id", id)
                .query((rs, row) -> mapTransaction(rs))
                .optional()
                .orElseThrow(() -> new NotFound("request-to-pay not found"));
    }

    private Transaction resolve(Transaction pending, String reason) {
        return conclude(pending.id(), TransactionKind.REQUEST_TO_PAY, TransactionStatus.FAILED, pending.fromWalletId(),
                pending.toWalletId(), pending.amountNgwee(), pending.reference(), reason, pending.createdAt(), now(),
                pending.expiresAt());
    }

    // ------------------------------------------------------------------ reading

    /** The most recent transactions, newest first, for the sim console. */
    @Transactional(readOnly = true)
    public List<Transaction> recentTransactions(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new BadRequest("limit must be 1 to 1000");
        }
        return jdbc.sql("select " + TRANSACTION_COLUMNS + " from transaction order by created_at desc, id limit :limit")
                .param("limit", limit)
                .query((rs, row) -> mapTransaction(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public Transaction transaction(UUID id) {
        return jdbc.sql("select " + TRANSACTION_COLUMNS + " from transaction where id = :id")
                .param("id", id)
                .query((rs, row) -> mapTransaction(rs))
                .optional()
                .orElseThrow(() -> new NotFound("transaction not found"));
    }

    private static Transaction mapTransaction(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        return new Transaction(
                rs.getObject("id", UUID.class),
                TransactionKind.valueOf(rs.getString("kind")),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getObject("from_wallet_id", UUID.class),
                rs.getObject("to_wallet_id", UUID.class),
                rs.getLong("amount_ngwee"),
                rs.getString("reference"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant(),
                expires == null ? null : expires.toInstant());
    }

    /**
     * A wallet's ledger lines, oldest first, from the start of {@code from} to the end of {@code to}
     * (Africa/Lusaka dates, both inclusive; either may be null).
     */
    @Transactional(readOnly = true)
    public List<StatementLine> statement(UUID walletId, LocalDate from, LocalDate to, int limit) {
        wallet(walletId);
        if (limit < 1 || limit > 5000) {
            throw new BadRequest("limit must be 1 to 5000");
        }
        OffsetDateTime start = from == null ? null : from.atStartOfDay(LUSAKA).toOffsetDateTime();
        OffsetDateTime end = to == null ? null : to.plusDays(1).atStartOfDay(LUSAKA).toOffsetDateTime();
        return jdbc.sql("select l.id, l.transaction_id, t.kind, l.amount_ngwee, l.balance_after_ngwee, "
                        + "case when l.wallet_id = t.from_wallet_id then t.to_wallet_id else t.from_wallet_id end "
                        + "as counterparty, t.reference, l.created_at "
                        + "from ledger_line l join transaction t on t.id = l.transaction_id "
                        + "where l.wallet_id = :wallet "
                        + "and (cast(:start as timestamptz) is null or l.created_at >= :start) "
                        + "and (cast(:end as timestamptz) is null or l.created_at < :end) "
                        + "order by l.id limit :limit")
                .param("wallet", walletId).param("start", start).param("end", end).param("limit", limit)
                .query((rs, row) -> new StatementLine(
                        rs.getLong("id"),
                        rs.getObject("transaction_id", UUID.class),
                        TransactionKind.valueOf(rs.getString("kind")),
                        rs.getLong("amount_ngwee"),
                        rs.getLong("balance_after_ngwee"),
                        rs.getObject("counterparty", UUID.class),
                        rs.getString("reference"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
