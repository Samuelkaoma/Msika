package zm.msika.core.payments;

import zm.msika.ledger.LedgerEntry;

/**
 * Something that must happen when a payment request reaches its final state, in the same database
 * transaction as the callback. The deposit lifecycle (card M-15) is the first user; the levy sweep and loan
 * repayments will be others. {@link HandlePaymentEvent} calls it and knows nothing of what it does.
 */
public interface PaymentOutcomes {

    /** Nothing extra happens. */
    PaymentOutcomes NONE = new PaymentOutcomes() {
        @Override
        public void succeeded(PaymentRequest request, LedgerEntry entry) {}

        @Override
        public void failed(PaymentRequest request, String reason) {}
    };

    /** The payment happened and {@code entry} is on the ledger. */
    void succeeded(PaymentRequest request, LedgerEntry entry);

    /** The payment did not happen. */
    void failed(PaymentRequest request, String reason);
}
