package zm.msika.core.deposit;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;

/**
 * Use cases that change a deposit by hand: the agent cancels it, or the trader disputes it. Each is one
 * atomic status change that only works from the right starting status.
 */
public final class ChangeDeposit {

    private static final int MAX_REASON = 200;

    private final DepositRepository deposits;
    private final Clock clock;

    public ChangeDeposit(DepositRepository deposits, Clock clock) {
        this.deposits = Objects.requireNonNull(deposits);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * The agent changes their mind before the payment is confirmed. The payments system may still complete the
     * payment (Msika cannot recall it); if it does, the money is recorded and the deposit is flagged DISPUTED.
     *
     * @throws ConflictException if the deposit is no longer PENDING
     */
    public Deposit cancel(UUID councilId, UUID depositId) {
        require(councilId, depositId);
        if (!deposits.transition(councilId, depositId, DepositStatus.PENDING, DepositStatus.CANCELLED,
                "CANCELLED_BY_AGENT", null, clock.instant())) {
            throw new ConflictException("only a PENDING deposit can be cancelled; this one is "
                    + require(councilId, depositId).status());
        }
        return require(councilId, depositId);
    }

    /**
     * The trader says the deposit is wrong ("NO 8F2K"). It is flagged for the council. Nothing on the ledger
     * changes: a person decides what to do.
     *
     * @throws ConflictException if the deposit is not CONFIRMED
     */
    public Deposit dispute(UUID councilId, UUID depositId, String reason) {
        require(councilId, depositId);
        String why = reason == null || reason.isBlank() ? "DISPUTED_BY_TRADER" : reason.trim();
        if (why.length() > MAX_REASON) {
            throw new InvalidInputException("the reason must be at most " + MAX_REASON + " characters");
        }
        if (!deposits.transition(councilId, depositId, DepositStatus.CONFIRMED, DepositStatus.DISPUTED, why, null,
                clock.instant())) {
            throw new ConflictException("only a CONFIRMED deposit can be disputed; this one is "
                    + require(councilId, depositId).status());
        }
        return require(councilId, depositId);
    }

    private Deposit require(UUID councilId, UUID depositId) {
        return deposits.find(councilId, depositId).orElseThrow(() -> new NotFoundException("no deposit " + depositId));
    }
}
