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
 */
public record PaymentEvent(
        String callbackId,
        String transactionId,
        Status status,
        long amountNgwee,
        String reference,
        String failureReason,
        Instant completedAt) {

    public enum Status {
        SUCCEEDED, FAILED
    }

    public PaymentEvent {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }
}
