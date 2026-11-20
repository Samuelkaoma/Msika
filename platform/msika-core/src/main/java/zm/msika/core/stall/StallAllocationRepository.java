package zm.msika.core.stall;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Where stall allocations are kept. Implemented in msika-api. */
public interface StallAllocationRepository {

    /**
     * Records a new allocation.
     *
     * @throws zm.msika.core.common.ConflictException if the stall already has an active allocation
     */
    void add(StallAllocation allocation);

    /** The allocation that has not ended, if any. */
    Optional<StallAllocation> findActive(UUID councilId, UUID stallId);

    /** Ends an allocation at the given time. The row is kept as history. */
    void end(UUID councilId, UUID allocationId, Instant at);
}
