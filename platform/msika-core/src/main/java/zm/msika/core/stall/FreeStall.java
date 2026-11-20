package zm.msika.core.stall;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import zm.msika.core.common.NotFoundException;

/**
 * Use case: free a stall, ending the trader's allocation. The allocation row is kept as history.
 *
 * <p>Repeating it is safe: freeing a stall that is not allocated does nothing.
 */
public final class FreeStall {

    private final StallRepository stalls;
    private final StallAllocationRepository allocations;
    private final Clock clock;

    public FreeStall(StallRepository stalls, StallAllocationRepository allocations, Clock clock) {
        this.stalls = Objects.requireNonNull(stalls);
        this.allocations = Objects.requireNonNull(allocations);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @return true if the stall was allocated and is now free, false if there was nothing to free
     * @throws NotFoundException if the stall is not in this council
     */
    public boolean execute(UUID councilId, UUID stallId) {
        Stall stall = stalls.find(councilId, stallId)
                .orElseThrow(() -> new NotFoundException("stall not found"));
        if (stall.status() != StallStatus.ALLOCATED) {
            return false;
        }
        Optional<StallAllocation> active = allocations.findActive(councilId, stallId);
        active.ifPresent(a -> allocations.end(councilId, a.id(), clock.instant()));
        stalls.setStatus(councilId, stallId, StallStatus.VACANT);
        return true;
    }
}
