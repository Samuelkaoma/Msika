package zm.msika.core.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where payment requests are kept. Implemented in msika-api. */
public interface PaymentRequestRepository {

    void add(PaymentRequest request);

    Optional<PaymentRequest> find(UUID councilId, UUID id);

    /**
     * Finds the request and locks its row until the transaction ends, so two callbacks for the same
     * payment (a duplicate, or a retry) are handled one after the other, never at the same time.
     */
    Optional<PaymentRequest> findForUpdate(UUID councilId, UUID id);

    /** Remembers the payments system's id for a request, if it does not have one yet. */
    void attachTransaction(UUID councilId, UUID id, String paymentsTransactionId);

    void markSucceeded(UUID councilId, UUID id, String paymentsTransactionId, Instant at);

    void markFailed(UUID councilId, UUID id, String paymentsTransactionId, String reason, Instant at);

    /** Requests still waiting for an outcome, oldest first. */
    List<PaymentRequest> listSent(UUID councilId);
}
