package zm.msika.core.payments;

import java.time.Instant;
import java.util.Objects;

/**
 * What the payments system tells Msika in a callback: a transaction reached its final state.
 *
 * @param callbackId the payments system's id for this callback, the same for every delivery of it
 * @param transactionId the payments system's id for the transaction
 * @param status {@code SUCCEEDED} or {@code FAILED}
 * @param amountNgwee the amount in whole ngwee
 * @param reference what Msika put on the request (see {@link PaymentReference}), or null
 * @param failureReason why it failed, when it did
 * @param completedAt when the payments system settled it
 * @param kind what sort of transaction it was (CASH_IN, TRANSFER, REQUEST_TO_PAY ...), or null if not stated
 * @param fromWalletId the wallet money left, or null
 * @param toWalletId the wallet money entered, or null
 */
public record PaymentEvent(
        String callbackId,
        String transactionId,
        Status status,
        long amountNgwee,
        String reference,
        String failureReason,
        Instant completedAt,
        String kind,
        String fromWalletId,
        String toWalletId) {

    public enum Status {
        SUCCEEDED, FAILED
    }

    /** An event that is only about a request Msika made, so it needs no wallet details. */
    public PaymentEvent(String callbackId, String transactionId, Status status, long amountNgwee, String reference,
            String failureReason, Instant completedAt) {
        this(callbackId, transactionId, status, amountNgwee, reference, failureReason, completedAt, null, null, null);
    }

    public PaymentEvent {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }
}
