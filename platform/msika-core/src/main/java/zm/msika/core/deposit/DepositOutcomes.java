package zm.msika.core.deposit;

import java.time.Clock;
import java.util.Objects;
import zm.msika.core.payments.PaymentOutcomes;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.ledger.LedgerEntry;

/**
 * Moves a deposit along when its payment request reaches its final state.
 *
 * <p>The tricky case is the callback that arrives after the agent cancelled. The payments system really moved
 * the money, so the ledger records it (the ledger follows what happened, not what was meant), and the deposit is
 * flagged DISPUTED so the council notices and sorts it out with the agent and the trader. A cancel and a
 * confirmation arriving at the same instant cannot both win: each is one atomic status change from the status it
 * expects, and the one that loses tries the other path.
 */
public final class DepositOutcomes implements PaymentOutcomes {

    public static final String PAID_AFTER_CANCEL = "PAID_AFTER_CANCEL";

    private final DepositRepository deposits;
    private final Clock clock;

    public DepositOutcomes(DepositRepository deposits, Clock clock) {
        this.deposits = Objects.requireNonNull(deposits);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void succeeded(PaymentRequest request, LedgerEntry entry) {
        deposits.findByPaymentRequest(request.councilId(), request.id()).ifPresent(deposit -> {
            var now = clock.instant();
            boolean confirmed = deposits.transition(request.councilId(), deposit.id(), DepositStatus.PENDING,
                    DepositStatus.CONFIRMED, null, entry.id(), now);
            if (!confirmed) {
                // Cancelled in the meantime: the money moved anyway. Record that, and flag it.
                deposits.transition(request.councilId(), deposit.id(), DepositStatus.CANCELLED, DepositStatus.DISPUTED,
                        PAID_AFTER_CANCEL, entry.id(), now);
            }
            // Any other status (CONFIRMED, DISPUTED, FAILED) means this is a repeat or a contradiction: leave it.
        });
    }

    @Override
    public void failed(PaymentRequest request, String reason) {
        deposits.findByPaymentRequest(request.councilId(), request.id()).ifPresent(deposit ->
                // A cancelled deposit stays cancelled: both the agent and the payments system agree nothing happened.
                deposits.transition(request.councilId(), deposit.id(), DepositStatus.PENDING, DepositStatus.FAILED,
                        reason, null, clock.instant()));
    }
}
