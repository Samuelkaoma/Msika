package zm.msika.core.deposit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where deposits are kept. Implemented in msika-api. */
public interface DepositRepository {

    /**
     * Adds the deposit.
     *
     * @return false, adding nothing, if the reference is already used that day in the council or the agent has
     *     already used that idempotency key
     */
    boolean add(Deposit deposit);

    Optional<Deposit> find(UUID councilId, UUID id);

    Optional<Deposit> findByPaymentRequest(UUID councilId, UUID paymentRequestId);

    Optional<Deposit> findByKey(UUID councilId, UUID agentId, String idempotencyKey);

    /** Deposits with this reference made on or after the date, newest first (references repeat only across days). */
    List<Deposit> findByReference(UUID councilId, String reference, java.time.LocalDate since);

    /** The council's deposits, newest first, optionally only those with one status. */
    List<Deposit> list(UUID councilId, DepositStatus status, int limit);

    /**
     * Changes the status only if it is still {@code from}, as one atomic step, so a cancel and a payment
     * confirmation arriving together cannot both win.
     *
     * @param ledgerEntryId the entry to link, or {@code null} to leave it as it is
     * @return true if the deposit was in {@code from} and is now in {@code to}
     */
    boolean transition(UUID councilId, UUID id, DepositStatus from, DepositStatus to, String reason, Long ledgerEntryId,
            Instant at);
}
