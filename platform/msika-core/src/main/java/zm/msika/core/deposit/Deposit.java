package zm.msika.core.deposit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import zm.msika.ledger.Money;

/**
 * A closing-time cash deposit: an agent took cash from a trader and asked the payments system to put it in the
 * trader's wallet.
 *
 * @param id the deposit's id
 * @param councilId the council
 * @param stallId the stall the money is recorded against
 * @param agentId the agent who took the cash
 * @param amount the amount, in ZMW
 * @param status where it stands
 * @param reference four characters the trader can quote ("Reply NO 8F2K"); unique per council per business date
 * @param businessDate the Africa/Lusaka day it was made
 * @param paymentRequestId the payment request that asks the payments system to move the money
 * @param ledgerEntryId the ledger entry, once the payment is confirmed; otherwise {@code null}
 * @param statusReason why it is FAILED, CANCELLED or DISPUTED; otherwise {@code null}
 * @param idempotencyKey the agent's key for the command, so sending it twice makes one deposit
 * @param createdAt when it was made
 * @param updatedAt when its status last changed
 */
public record Deposit(
        UUID id,
        UUID councilId,
        UUID stallId,
        UUID agentId,
        Money amount,
        DepositStatus status,
        String reference,
        LocalDate businessDate,
        UUID paymentRequestId,
        Long ledgerEntryId,
        String statusReason,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt) {

    public Deposit {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(stallId, "stallId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(paymentRequestId, "paymentRequestId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
