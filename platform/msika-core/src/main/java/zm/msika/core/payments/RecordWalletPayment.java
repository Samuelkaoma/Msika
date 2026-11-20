package zm.msika.core.payments;

import java.util.Objects;
import java.util.Optional;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * Use case: the payments system reports a payment that Msika did not ask for, between wallets it knows
 * (ARCHITECTURE.md section 4.2). Two kinds are recorded, both as VERIFIED because the payments system itself
 * vouches for them:
 *
 * <ul>
 *   <li>A payment <strong>into a stall's MERCHANT wallet</strong> is a MERCHANT_SALE for that stall.
 *   <li>A payment <strong>from a stall's wallet to a SUPPLIER wallet</strong> is a SUPPLIER_PAYMENT for that stall.
 * </ul>
 *
 * Only a transfer or a request-to-pay is considered: a cash-in is a deposit (Msika's own, matched by reference), and
 * funding or withdrawing is not trade. Anything else, and anything that failed, is not Msika's business and is
 * ignored. Like every callback this is idempotent: the ledger key is the payments transaction id.
 */
public final class RecordWalletPayment {

    private static final java.util.Set<String> TRADE_KINDS = java.util.Set.of("TRANSFER", "REQUEST_TO_PAY");

    private final WalletRegistry wallets;
    private final RecordEntry recordEntry;

    public RecordWalletPayment(WalletRegistry wallets, RecordEntry recordEntry) {
        this.wallets = Objects.requireNonNull(wallets);
        this.recordEntry = Objects.requireNonNull(recordEntry);
    }

    /** What this class decided a callback is, before anything is recorded. */
    public record Classified(WalletOwner owner, EntryKind kind) {}

    /**
     * Works out whether the event is a sale or a supplier payment, and for which council and stall. This only
     * reads the wallet registry, so it can be done before a database transaction for the right council is opened.
     */
    public Optional<Classified> classify(PaymentEvent event) {
        if (event.status() != PaymentEvent.Status.SUCCEEDED || event.kind() == null
                || !TRADE_KINDS.contains(event.kind()) || event.toWalletId() == null) {
            return Optional.empty();
        }
        Optional<WalletOwner> to = wallets.find(event.toWalletId());
        if (to.isEmpty()) {
            return Optional.empty();
        }
        if (to.get().role() == WalletOwner.Role.MERCHANT) {
            return Optional.of(new Classified(to.get(), EntryKind.MERCHANT_SALE));
        }
        if (to.get().role() == WalletOwner.Role.SUPPLIER && event.fromWalletId() != null) {
            Optional<WalletOwner> from = wallets.find(event.fromWalletId());
            if (from.isPresent()
                    && (from.get().role() == WalletOwner.Role.TRADER || from.get().role() == WalletOwner.Role.MERCHANT)
                    && from.get().councilId().equals(to.get().councilId())) {
                return Optional.of(new Classified(from.get(), EntryKind.SUPPLIER_PAYMENT));
            }
        }
        return Optional.empty();
    }

    /**
     * Records a classified event. Call inside a transaction for {@code classified.owner().councilId()}.
     *
     * @return true if this call recorded it, false if it was already recorded (a repeat)
     */
    public boolean execute(PaymentEvent event, Classified classified) {
        RecordEntry.Recorded recorded = recordEntry.execute(new NewEntry(
                classified.owner().councilId(),
                classified.owner().stallId(),
                classified.kind(),
                Provenance.VERIFIED,
                Money.ofMinor(event.amountNgwee(), Currency.ZMW),
                event.completedAt(),
                HandlePaymentEvent.ledgerKey(event.transactionId()),
                null));
        return !recorded.duplicate();
    }
}
