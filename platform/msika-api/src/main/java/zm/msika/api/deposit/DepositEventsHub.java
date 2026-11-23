package zm.msika.api.deposit;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.deposit.DepositEvents;

/**
 * Passes each deposit outcome to everything that acts on it (the SMS texts, the levy sweep), in the same
 * transaction as the change. Each of those implements {@link Part}; this hub is the {@link DepositEvents} the deposit
 * use cases are given.
 */
@Component
@Primary
public class DepositEventsHub implements DepositEvents {

    /** Something that acts on deposit outcomes. */
    public interface Part extends DepositEvents {}

    private final List<Part> parts;

    DepositEventsHub(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    @Override
    public void confirmed(Deposit deposit) {
        parts.forEach(p -> p.confirmed(deposit));
    }

    @Override
    public void failed(Deposit deposit, String reason) {
        parts.forEach(p -> p.failed(deposit, reason));
    }

    @Override
    public void cancelled(Deposit deposit) {
        parts.forEach(p -> p.cancelled(deposit));
    }
}
