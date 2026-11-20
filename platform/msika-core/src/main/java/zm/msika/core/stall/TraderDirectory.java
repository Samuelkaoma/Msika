package zm.msika.core.stall;

import java.util.UUID;

/**
 * Asks whether a trader exists in a council. Traders are registered in card M-10; until then this is
 * all the stall use cases need to know about them.
 */
public interface TraderDirectory {

    boolean exists(UUID councilId, UUID traderId);
}
