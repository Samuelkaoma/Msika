package zm.msika.core.payments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import zm.msika.ledger.Money;

/**
 * A request Msika has made to the payments system, and what became of it. Msika saves this BEFORE it calls
 * the payments system, so if the call or the callback goes missing there is still a record of what was asked.
 *
 * @param id Msika's id for the request; it appears in the reference the payments system echoes back
 * @param councilId the council whose ledger the outcome belongs to
 * @param purpose why money is being moved
 * @param stallId the stall the money is recorded against
 * @param amount the amount asked for
 * @param status where it stands
 * @param paymentsTransactionId the payments system's id, once known
 * @param failureReason why it failed, when it did
 * @param createdAt when Msika made the request
 * @param completedAt when its outcome was recorded, or {@code null} while it is still SENT
 */
public record PaymentRequest(
        UUID id,
        UUID councilId,
        Purpose purpose,
        UUID stallId,
        Money amount,
        Status status,
        String paymentsTransactionId,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {

    /** Why Msika is moving money. Later cards add levy sweeps and loan repayments. */
    public enum Purpose {
        /** An agent's cash deposit for a stall. */
        DEPOSIT
    }

    public enum Status {
        /** Sent to the payments system, outcome not yet known. */
        SENT,
        /** The payments system confirmed the money moved, and the ledger entry is recorded. */
        SUCCEEDED,
        /** The payments system said it did not happen. Nothing is on the ledger. */
        FAILED
    }

    public PaymentRequest {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(stallId, "stallId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("the amount must be greater than zero");
        }
    }

    /** True once the outcome is known: the request will not change again. */
    public boolean isFinal() {
        return status != Status.SENT;
    }

    /** The reference sent to the payments system and echoed back in its callback. */
    public String reference() {
        return PaymentReference.of(councilId, id);
    }
}
