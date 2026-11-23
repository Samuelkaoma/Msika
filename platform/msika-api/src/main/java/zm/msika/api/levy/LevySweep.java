package zm.msika.api.levy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import zm.msika.api.deposit.DepositEventsHub;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.levy.LevyAuthorityPort;
import zm.msika.core.levy.LevyAuthorityUnavailableException;
import zm.msika.core.levy.LevySweepPlan;
import zm.msika.core.payments.PaymentOutcomes;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsUnavailableException;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;

/**
 * The levy sweep (ARCHITECTURE.md section 4.3, card M-24). Three steps, each safe to repeat:
 *
 * <ol>
 *   <li><strong>On a confirmed deposit</strong>, in the same transaction: if the trader consented, choose the unpaid
 *       levy days the deposit covers (oldest first, whole days), reserve them, and save a LEVY payment request and a
 *       {@code PENDING} instruction. One instruction per deposit.
 *   <li><strong>Send</strong> (the worker, outside any transaction): ask the payments system to move the amount from
 *       the trader's wallet to the council's collection wallet, keyed by the request, so it moves once. The outcome
 *       arrives by callback: success records {@code LEVY_PAYMENT} (VERIFIED) on the ledger and marks the instruction
 *       {@code MOVED}; failure marks it {@code FAILED} and frees the levy days to be paid by a later deposit.
 *   <li><strong>Tell the authority</strong> (the worker): credit the trader's eLevy account, keyed by the payments
 *       transaction. If the authority is down after the money moved, the instruction stays {@code MOVED} and is tried
 *       again; the money is never moved twice and the account never credited twice.
 * </ol>
 *
 * Msika never holds the money (AGENTS.md rule 8): it moves from the trader's wallet straight to the council's.
 */
@Component
@Order(20)
public class LevySweep implements DepositEventsHub.Part, PaymentOutcomes {

    private static final Logger LOG = LoggerFactory.getLogger(LevySweep.class);

    private final JdbcClient jdbc;
    private final TenantContext tenant;
    private final PaymentRequestRepository requests;
    private final WalletRegistry wallets;
    private final PaymentsPort payments;
    private final LevyAuthorityPort authority;
    private final LevyAccounts accounts;
    private final Clock clock;

    LevySweep(JdbcClient jdbc, TenantContext tenant, PaymentRequestRepository requests, WalletRegistry wallets,
            PaymentsPort payments, LevyAuthorityPort authority, LevyAccounts accounts, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.requests = requests;
        this.wallets = wallets;
        this.payments = payments;
        this.authority = authority;
        this.accounts = accounts;
        this.clock = clock;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Step 1: on a confirmed deposit (inside the callback's transaction, as the deposit's council)
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void confirmed(Deposit deposit) {
        UUID council = deposit.councilId();
        Optional<UUID> trader = jdbc.sql("select a.trader_id from stall_allocation a join trader t on t.id = a.trader_id "
                        + "where a.council_id = :c and a.stall_id = :s and a.valid_to is null "
                        + "and t.consent_levy_sweep_at is not null")
                .param("c", council).param("s", deposit.stallId()).query(UUID.class).optional();
        if (trader.isEmpty()) {
            return; // nobody holds the stall, or the holder has not consented: nothing is swept
        }
        boolean already = jdbc.sql("select exists (select 1 from payment_instruction where deposit_id = :d)")
                .param("d", deposit.id()).query(Boolean.class).single();
        if (already) {
            return;
        }
        List<LevySweepPlan.Unpaid> unpaid = jdbc.sql("select id, business_date, amount_ngwee from levy_charge "
                        + "where council_id = :c and stall_id = :s and trader_id = :t and settled_by_instruction_id is null "
                        + "order by business_date for update")
                .param("c", council).param("s", deposit.stallId()).param("t", trader.get())
                .query((rs, n) -> new LevySweepPlan.Unpaid(rs.getObject("id", UUID.class),
                        rs.getObject("business_date", LocalDate.class), rs.getLong("amount_ngwee")))
                .list();
        List<LevySweepPlan.Unpaid> chosen = LevySweepPlan.choose(unpaid, deposit.amount().minorUnits());
        if (chosen.isEmpty()) {
            return;
        }
        long total = LevySweepPlan.total(chosen);
        Instant now = clock.instant();
        PaymentRequest request = new PaymentRequest(UUID.randomUUID(), council, PaymentRequest.Purpose.LEVY,
                deposit.stallId(), Money.ofMinor(total, Currency.ZMW), PaymentRequest.Status.SENT, null, null, now, null);
        requests.add(request);
        UUID instruction = UUID.randomUUID();
        jdbc.sql("insert into payment_instruction (id, council_id, purpose, deposit_id, stall_id, trader_id, "
                        + "payment_request_id, amount_ngwee, status, created_at, updated_at) "
                        + "values (:id, :c, 'LEVY', :d, :s, :t, :r, :amount, 'PENDING', :at, :at)")
                .param("id", instruction).param("c", council).param("d", deposit.id()).param("s", deposit.stallId())
                .param("t", trader.get()).param("r", request.id()).param("amount", total).param("at", utc(now))
                .update();
        jdbc.sql("update levy_charge set settled_by_instruction_id = :i where council_id = :c and id in (:ids)")
                .param("i", instruction).param("c", council)
                .param("ids", chosen.stream().map(LevySweepPlan.Unpaid::chargeId).toList()).update();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The transfer's outcome (inside the callback's transaction)
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void succeeded(PaymentRequest request, LedgerEntry entry) {
        if (request.purpose() != PaymentRequest.Purpose.LEVY) {
            return;
        }
        jdbc.sql("update payment_instruction set status = 'MOVED', updated_at = :at where council_id = :c "
                        + "and payment_request_id = :r and status in ('PENDING', 'SENT')")
                .param("at", utc(clock.instant())).param("c", request.councilId()).param("r", request.id()).update();
    }

    @Override
    public void failed(PaymentRequest request, String reason) {
        if (request.purpose() != PaymentRequest.Purpose.LEVY) {
            return;
        }
        Optional<UUID> instruction = jdbc.sql("update payment_instruction set status = 'FAILED', last_error = :e, "
                        + "updated_at = :at where council_id = :c and payment_request_id = :r and status in ('PENDING', 'SENT') "
                        + "returning id")
                .param("e", reason).param("at", utc(clock.instant())).param("c", request.councilId())
                .param("r", request.id()).query(UUID.class).optional();
        // The days were not paid: free them for the next deposit.
        instruction.ifPresent(id -> jdbc.sql("update levy_charge set settled_by_instruction_id = null "
                        + "where council_id = :c and settled_by_instruction_id = :i")
                .param("c", request.councilId()).param("i", id).update());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Steps 2 and 3: the worker
    // ---------------------------------------------------------------------------------------------------------

    private record Open(UUID id, UUID councilId, UUID stallId, UUID traderId, UUID requestId, long amountNgwee,
            String status, String paymentsTxn, UUID marketId) {}

    /** Moves every open instruction one step on. Returns how many were moved on. */
    public int runOnce() {
        List<Open> open = new java.util.ArrayList<>();
        tenant.forEachCouncil(council -> open.addAll(jdbc.sql("select i.id, i.council_id, i.stall_id, i.trader_id, "
                        + "i.payment_request_id, i.amount_ngwee, i.status, r.payments_transaction_id, s.market_id "
                        + "from payment_instruction i join payment_request r on r.id = i.payment_request_id "
                        + "join stall s on s.id = i.stall_id "
                        + "where i.council_id = :c and i.status in ('PENDING', 'MOVED') order by i.created_at limit 200")
                .param("c", council)
                .query((rs, n) -> new Open(rs.getObject("id", UUID.class), rs.getObject("council_id", UUID.class),
                        rs.getObject("stall_id", UUID.class), rs.getObject("trader_id", UUID.class),
                        rs.getObject("payment_request_id", UUID.class), rs.getLong("amount_ngwee"), rs.getString("status"),
                        rs.getString("payments_transaction_id"), rs.getObject("market_id", UUID.class)))
                .list()));
        int moved = 0;
        for (Open o : open) {
            try {
                boolean progressed = "PENDING".equals(o.status()) ? send(o) : tellAuthority(o);
                moved += progressed ? 1 : 0;
            } catch (RuntimeException e) {
                LOG.error("levy instruction {} could not move on", o.id(), e);
                note(o, e.getMessage());
            }
        }
        return moved;
    }

    private boolean send(Open o) {
        Optional<WalletOwner> from = tenant.inCouncil(o.councilId(),
                () -> wallets.ofStall(o.councilId(), o.stallId(), WalletOwner.Role.TRADER));
        Optional<WalletOwner> to = tenant.inCouncil(o.councilId(), () -> wallets.ofCouncil(o.councilId()));
        if (from.isEmpty() || to.isEmpty()) {
            note(o, from.isEmpty() ? "the stall has no trader wallet" : "the council has no collection wallet");
            return false;
        }
        PaymentRequest request = tenant.inCouncil(o.councilId(), () -> requests.find(o.councilId(), o.requestId()))
                .orElseThrow();
        String txn;
        try {
            txn = payments.transfer("msika-levy-" + o.requestId(), from.get().walletId(), to.get().walletId(),
                    request.amount(), request.reference());
        } catch (PaymentsUnavailableException e) {
            note(o, e.getMessage());
            return false;
        }
        tenant.inCouncil(o.councilId(), () -> {
            requests.attachTransaction(o.councilId(), o.requestId(), txn);
            // The callback may already have moved it on; only a PENDING one becomes SENT.
            jdbc.sql("update payment_instruction set status = 'SENT', updated_at = :at where id = :id and status = 'PENDING'")
                    .param("at", utc(clock.instant())).param("id", o.id()).update();
        });
        return true;
    }

    private boolean tellAuthority(Open o) {
        String accountId;
        try {
            long daily = tenant.inCouncil(o.councilId(), () -> jdbc.sql("select amount_ngwee from levy_charge "
                            + "where council_id = :c and settled_by_instruction_id = :i order by business_date desc limit 1")
                    .param("c", o.councilId()).param("i", o.id()).query(Long.class).single());
            accountId = accounts.ensure(o.councilId(), o.traderId(), o.marketId(), daily);
            authority.topUp(accountId, "msika-levy-" + o.paymentsTxn(), o.amountNgwee(), "msika levy " + o.id());
        } catch (LevyAuthorityUnavailableException e) {
            note(o, e.getMessage());
            return false;
        }
        String account = accountId;
        tenant.inCouncil(o.councilId(), () -> jdbc.sql("update payment_instruction set status = 'DONE', levy_account_id = :a, "
                        + "last_error = null, updated_at = :at where id = :id and status = 'MOVED'")
                .param("a", account).param("at", utc(clock.instant())).param("id", o.id()).update());
        return true;
    }

    private void note(Open o, String error) {
        String e = error == null ? "unknown error" : error.length() > 300 ? error.substring(0, 300) : error;
        tenant.inCouncil(o.councilId(), () -> jdbc.sql("update payment_instruction set attempts = attempts + 1, "
                        + "last_error = :e, updated_at = :at where id = :id")
                .param("e", e).param("at", utc(clock.instant())).param("id", o.id()).update());
    }
}
