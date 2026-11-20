package zm.msika.zamsim.wallet;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 *   <li>if the paying wallet cannot afford it, the transaction is recorded as FAILED and nothing moves;
 *   <li>otherwise both balances change and two ledger lines are written that add up to zero.
 * </ol>
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

    private final JdbcClient jdbc;
    private final Clock clock;

    WalletService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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

    private Optional<Wallet> findWallet(UUID id) {
        return jdbc.sql("select id, type, name, msisdn, balance_ngwee, created_at from wallet where id = :id")
                .param("id", id)
                .query((rs, row) -> new Wallet(
                        rs.getObject("id", UUID.class),
                        WalletType.valueOf(rs.getString("type")),
                        rs.getString("name"),
                        rs.getString("msisdn"),
                        rs.getLong("balance_ngwee"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
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

    private Transaction move(String key, TransactionKind kind, UUID from, UUID to, long amount, String reference) {
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

        String fingerprint = kind + "|" + from + "|" + to + "|" + amount + "|" + (reference == null ? "" : reference);
        // Claim the key. If another request holds it, Postgres waits here until that request commits.
        boolean first = jdbc.sql("insert into idempotency_record (idempotency_key, fingerprint, created_at) "
                        + "values (:key, :fp, :at) on conflict do nothing returning idempotency_key")
                .param("key", key).param("fp", fingerprint)
                .param("at", OffsetDateTime.ofInstant(now(), ZoneOffset.UTC))
                .query(String.class).optional().isPresent();
        if (!first) {
            return repeat(key, fingerprint);
        }

        Transaction transaction = process(kind, from, to, amount, reference);
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

    private Transaction process(TransactionKind kind, UUID fromId, UUID toId, long amount, String reference) {
        // Lock both wallets, lower id first, so opposite transfers cannot deadlock.
        List<Wallet> locked = jdbc.sql("select id, type, name, msisdn, balance_ngwee, created_at from wallet "
                        + "where id in (:a, :b) order by id for update")
                .param("a", fromId).param("b", toId)
                .query((rs, row) -> new Wallet(
                        rs.getObject("id", UUID.class),
                        WalletType.valueOf(rs.getString("type")),
                        rs.getString("name"),
                        rs.getString("msisdn"),
                        rs.getLong("balance_ngwee"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        Wallet from = locked.stream().filter(w -> w.id().equals(fromId)).findFirst()
                .orElseThrow(() -> new NotFound("wallet " + fromId + " not found"));
        Wallet to = locked.stream().filter(w -> w.id().equals(toId)).findFirst()
                .orElseThrow(() -> new NotFound("wallet " + toId + " not found"));
        checkKind(kind, from, to);

        UUID id = UUID.randomUUID();
        Instant now = now();
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        boolean canAfford = from.type() == WalletType.ISSUER || from.balanceNgwee() >= amount;
        if (!canAfford) {
            insertTransaction(id, kind, TransactionStatus.FAILED, fromId, toId, amount, reference, INSUFFICIENT_FUNDS, at);
            return new Transaction(id, kind, TransactionStatus.FAILED, fromId, toId, amount, reference,
                    INSUFFICIENT_FUNDS, now);
        }

        long fromAfter = Math.subtractExact(from.balanceNgwee(), amount);
        long toAfter = Math.addExact(to.balanceNgwee(), amount);
        insertTransaction(id, kind, TransactionStatus.SUCCEEDED, fromId, toId, amount, reference, null, at);
        jdbc.sql("update wallet set balance_ngwee = :b where id = :id").param("b", fromAfter).param("id", fromId).update();
        jdbc.sql("update wallet set balance_ngwee = :b where id = :id").param("b", toAfter).param("id", toId).update();
        insertLine(id, fromId, -amount, fromAfter, at);
        insertLine(id, toId, amount, toAfter, at);
        return new Transaction(id, kind, TransactionStatus.SUCCEEDED, fromId, toId, amount, reference, null, now);
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
        }
    }

    private void insertTransaction(UUID id, TransactionKind kind, TransactionStatus status, UUID from, UUID to,
            long amount, String reference, String failure, OffsetDateTime at) {
        jdbc.sql("insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, reference, "
                        + "failure_reason, created_at) values (:id, :kind, :status, :from, :to, :amount, :ref, :failure, :at)")
                .param("id", id).param("kind", kind.name()).param("status", status.name())
                .param("from", from).param("to", to).param("amount", amount).param("ref", reference)
                .param("failure", failure).param("at", at)
                .update();
    }

    private void insertLine(UUID transaction, UUID wallet, long amount, long balanceAfter, OffsetDateTime at) {
        jdbc.sql("insert into ledger_line (transaction_id, wallet_id, amount_ngwee, balance_after_ngwee, created_at) "
                        + "values (:tx, :wallet, :amount, :after, :at)")
                .param("tx", transaction).param("wallet", wallet).param("amount", amount)
                .param("after", balanceAfter).param("at", at)
                .update();
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Transaction transaction(UUID id) {
        return jdbc.sql("select id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, reference, "
                        + "failure_reason, created_at from transaction where id = :id")
                .param("id", id)
                .query((rs, row) -> new Transaction(
                        rs.getObject("id", UUID.class),
                        TransactionKind.valueOf(rs.getString("kind")),
                        TransactionStatus.valueOf(rs.getString("status")),
                        rs.getObject("from_wallet_id", UUID.class),
                        rs.getObject("to_wallet_id", UUID.class),
                        rs.getLong("amount_ngwee"),
                        rs.getString("reference"),
                        rs.getString("failure_reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElseThrow(() -> new NotFound("transaction not found"));
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
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
