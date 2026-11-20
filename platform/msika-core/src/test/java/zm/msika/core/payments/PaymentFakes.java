package zm.msika.core.payments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import zm.msika.core.payments.PaymentRequest.Status;

/** In-memory stand-ins for the payment ports. */
public final class PaymentFakes {

    private PaymentFakes() {}

    /** In-memory payment requests. */
    public static final class Requests implements PaymentRequestRepository {
        public final Map<UUID, PaymentRequest> byId = new HashMap<>();
        public final List<String> locked = new ArrayList<>();

        @Override
        public void add(PaymentRequest request) {
            byId.put(request.id(), request);
        }

        @Override
        public Optional<PaymentRequest> find(UUID councilId, UUID id) {
            return Optional.ofNullable(byId.get(id)).filter(r -> r.councilId().equals(councilId));
        }

        @Override
        public Optional<PaymentRequest> findForUpdate(UUID councilId, UUID id) {
            locked.add(id.toString());
            return find(councilId, id);
        }

        @Override
        public void attachTransaction(UUID councilId, UUID id, String txn) {
            PaymentRequest r = byId.get(id);
            if (r.paymentsTransactionId() == null) {
                byId.put(id, with(r, r.status(), txn, r.failureReason(), r.completedAt()));
            }
        }

        @Override
        public void markSucceeded(UUID councilId, UUID id, String txn, Instant at) {
            PaymentRequest r = byId.get(id);
            byId.put(id, with(r, Status.SUCCEEDED, txn, null, at));
        }

        @Override
        public void markFailed(UUID councilId, UUID id, String txn, String reason, Instant at) {
            PaymentRequest r = byId.get(id);
            byId.put(id, with(r, Status.FAILED, txn, reason, at));
        }

        @Override
        public List<PaymentRequest> listSent(UUID councilId) {
            return byId.values().stream().filter(r -> r.status() == Status.SENT).toList();
        }

        private static PaymentRequest with(PaymentRequest r, Status status, String txn, String reason, Instant at) {
            return new PaymentRequest(r.id(), r.councilId(), r.purpose(), r.stallId(), r.amount(), status, txn, reason,
                    r.createdAt(), at);
        }
    }

}
