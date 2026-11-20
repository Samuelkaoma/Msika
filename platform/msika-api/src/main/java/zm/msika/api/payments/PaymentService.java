package zm.msika.api.payments;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.HandlePaymentEvent;
import zm.msika.core.payments.PaymentEvent;
import zm.msika.core.payments.PaymentReference;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.RecordWalletPayment;
import zm.msika.ledger.Money;

/**
 * Starts payments and handles their outcomes.
 *
 * <p>Starting a deposit is three steps, and the order matters:
 *
 * <ol>
 *   <li>Save the request (committed), so there is a record of what was asked even if everything after fails.
 *   <li>Call the payments system, OUTSIDE any database transaction, so a slow reply holds no locks.
 *   <li>Remember the payments system's transaction id on the request.
 * </ol>
 *
 * <p>If step 2 fails or times out, the request stays SENT and can be sent again with the same idempotency key:
 * the payments system moves money once however many times it is asked.
 */
@Service
public class PaymentService {

    private final PaymentRequestRepository requests;
    private final PaymentsPort payments;
    private final HandlePaymentEvent handler;
    private final RecordWalletPayment walletPayments;
    private final TenantContext tenant;
    private final Clock clock;

    PaymentService(PaymentRequestRepository requests, PaymentsPort payments, HandlePaymentEvent handler,
            RecordWalletPayment walletPayments, TenantContext tenant, Clock clock) {
        this.requests = requests;
        this.payments = payments;
        this.handler = handler;
        this.walletPayments = walletPayments;
        this.tenant = tenant;
        this.clock = clock;
    }

    /** The idempotency key Msika uses with the payments system for a request. */
    static String paymentsKey(UUID requestId) {
        return "msika-" + requestId;
    }

    /**
     * Records the request and asks the payments system to move the money.
     *
     * @throws zm.msika.core.payments.PaymentsUnavailableException if the payments system could not be asked; the
     *     request is still saved as SENT and {@link #resend} can try again
     */
    public PaymentRequest startDeposit(UUID councilId, UUID stallId, Money amount, String agentWalletId,
            String customerWalletId) {
        PaymentRequest request = new PaymentRequest(UUID.randomUUID(), councilId, PaymentRequest.Purpose.DEPOSIT, stallId,
                amount, PaymentRequest.Status.SENT, null, null, clock.instant(), null);
        tenant.inCouncil(councilId, () -> requests.add(request));
        send(request, agentWalletId, customerWalletId);
        return request;
    }

    /** Sends a request again, for example after the payments system was unreachable. Safe to repeat. */
    public void resend(UUID councilId, UUID requestId, String agentWalletId, String customerWalletId) {
        PaymentRequest request = find(councilId, requestId)
                .orElseThrow(() -> new zm.msika.core.common.NotFoundException("no payment request " + requestId));
        if (!request.isFinal()) {
            send(request, agentWalletId, customerWalletId);
        }
    }

    /**
     * Asks the payments system to move the money for a saved request, then remembers its transaction id. Safe to
     * repeat: the key is derived from the request, so the payments system moves the money once.
     */
    public void send(PaymentRequest request, String agentWalletId, String customerWalletId) {
        String txn = payments.cashIn(paymentsKey(request.id()), agentWalletId, customerWalletId, request.amount(),
                request.reference());
        tenant.inCouncil(request.councilId(), () -> requests.attachTransaction(request.councilId(), request.id(), txn));
    }

    public Optional<PaymentRequest> find(UUID councilId, UUID requestId) {
        return tenant.inCouncil(councilId, () -> requests.find(councilId, requestId));
    }

    public List<PaymentRequest> listSent(UUID councilId) {
        return tenant.inCouncil(councilId, () -> requests.listSent(councilId));
    }

    /**
     * Handles a callback in one database transaction, as the right council. If anything fails the whole transaction
     * rolls back, so a callback is applied completely or not at all, and can safely be retried.
     *
     * <p>A callback about a request Msika made names its council in the reference. Any other callback is about a
     * payment between wallets (a customer paying a stall, a stall paying a supplier): the wallet registry says whose.
     */
    public HandlePaymentEvent.Outcome handle(PaymentEvent event) {
        Optional<PaymentReference.Parts> parts = PaymentReference.parse(event.reference());
        if (parts.isPresent()) {
            return tenant.inCouncil(parts.get().councilId(), () -> handler.execute(event));
        }
        Optional<RecordWalletPayment.Classified> classified = walletPayments.classify(event);
        if (classified.isEmpty()) {
            return HandlePaymentEvent.Outcome.UNMATCHED;
        }
        UUID councilId = classified.get().owner().councilId();
        boolean recorded = tenant.inCouncil(councilId, () -> walletPayments.execute(event, classified.get()));
        return recorded ? HandlePaymentEvent.Outcome.RECORDED : HandlePaymentEvent.Outcome.DUPLICATE;
    }
}
