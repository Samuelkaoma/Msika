package zm.msika.core.payments;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * Use case: the payments system says a transaction reached its final state. Every callback goes through
 * here, and through {@link RecordEntry} for the ledger (card M-14).
 *
 * <p>Callbacks are <strong>at least once</strong>: the same one can arrive twice, late, or out of order, and
 * a callback can arrive before Msika has finished recording that it made the request. So this is built to
 * be safe under all of that:
 *
 * <ul>
 *   <li>The request is found from the reference in the callback, never from anything that depends on timing.
 *   <li>The request's row is locked, so two copies of one callback are handled one after the other.
 *   <li>The ledger entry uses the payments system's transaction id as its idempotency key (AGENTS.md
 *       rule 5), so even if everything else failed, one payment can only ever be recorded once.
 *   <li>The first final outcome wins. A copy that says the same thing is a harmless duplicate.
 * </ul>
 *
 * <p>A SUCCEEDED callback whose amount is not the amount Msika asked for is NOT recorded: money moved that
 * Msika did not ask to move. It is reported as an error so the callback is retried and a person looks at it.
 */
public final class HandlePaymentEvent {

    /** What happened to the callback. */
    public enum Outcome {
        /** A confirmed payment was recorded on the ledger. */
        RECORDED,
        /** The payments system said the payment failed; the request is marked failed and nothing is on the ledger. */
        FAILURE_NOTED,
        /** An outcome for this request was already recorded: a harmless repeat. */
        DUPLICATE,
        /** The callback is not about a request Msika knows (another system's payment, or a bad reference). */
        UNMATCHED
    }

    private final PaymentRequestRepository requests;
    private final RecordEntry recordEntry;
    private final Clock clock;
    private final PaymentOutcomes outcomes;

    public HandlePaymentEvent(PaymentRequestRepository requests, RecordEntry recordEntry, Clock clock) {
        this(requests, recordEntry, clock, PaymentOutcomes.NONE);
    }

    /** @param outcomes told, in the same transaction, what became of each request (for example the deposit lifecycle) */
    public HandlePaymentEvent(PaymentRequestRepository requests, RecordEntry recordEntry, Clock clock,
            PaymentOutcomes outcomes) {
        this.requests = Objects.requireNonNull(requests);
        this.recordEntry = Objects.requireNonNull(recordEntry);
        this.clock = Objects.requireNonNull(clock);
        this.outcomes = Objects.requireNonNull(outcomes);
    }

    /**
     * The key under which a payments transaction is recorded on the ledger. It depends only on the
     * transaction id, so every callback for one transaction produces the same key.
     */
    public static String ledgerKey(String paymentsTransactionId) {
        return "zamsim:" + paymentsTransactionId;
    }

    /**
     * @throws IllegalStateException if a confirmed payment does not match what was asked for
     */
    public Outcome execute(PaymentEvent event) {
        Optional<PaymentReference.Parts> parts = PaymentReference.parse(event.reference());
        if (parts.isEmpty()) {
            return Outcome.UNMATCHED;
        }
        Optional<PaymentRequest> found = requests.findForUpdate(parts.get().councilId(), parts.get().requestId());
        if (found.isEmpty()) {
            return Outcome.UNMATCHED;
        }
        PaymentRequest request = found.get();

        if (request.isFinal()) {
            return Outcome.DUPLICATE;
        }

        if (event.status() == PaymentEvent.Status.FAILED) {
            String reason = event.failureReason() == null ? "FAILED" : event.failureReason();
            requests.markFailed(request.councilId(), request.id(), event.transactionId(), reason, event.completedAt());
            outcomes.failed(request, reason);
            return Outcome.FAILURE_NOTED;
        }

        if (event.amountNgwee() != request.amount().minorUnits()) {
            throw new IllegalStateException("the payments system confirmed " + event.amountNgwee() + " ngwee for request "
                    + request.id() + " but " + request.amount().minorUnits() + " ngwee was asked for");
        }

        RecordEntry.Recorded recorded = recordEntry.execute(new NewEntry(
                request.councilId(),
                request.stallId(),
                EntryKind.DEPOSIT,
                Provenance.DEPOSITED,
                Money.ofMinor(event.amountNgwee(), Currency.ZMW),
                event.completedAt(),
                ledgerKey(event.transactionId()),
                null));
        requests.markSucceeded(request.councilId(), request.id(), event.transactionId(), clock.instant());
        outcomes.succeeded(request, recorded.entry());
        return Outcome.RECORDED;
    }
}
