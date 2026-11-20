package zm.msika.core.stall;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;

/**
 * Use case: allocate a vacant stall to a trader.
 *
 * <p>Repeating the same request is safe: if the stall is already allocated to this same trader the
 * existing allocation is returned and nothing changes. A stall held by someone else, or suspended,
 * cannot be allocated.
 */
public final class AllocateStall {

    private final StallRepository stalls;
    private final StallAllocationRepository allocations;
    private final TraderDirectory traders;
    private final Clock clock;

    public AllocateStall(
            StallRepository stalls,
            StallAllocationRepository allocations,
            TraderDirectory traders,
            Clock clock) {
        this.stalls = Objects.requireNonNull(stalls);
        this.allocations = Objects.requireNonNull(allocations);
        this.traders = Objects.requireNonNull(traders);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @throws NotFoundException if the stall or the trader is not in this council
     * @throws ConflictException if the stall is held by another trader or is suspended
     */
    public StallAllocation execute(UUID councilId, UUID stallId, UUID traderId) {
        Stall stall = stalls.find(councilId, stallId)
                .orElseThrow(() -> new NotFoundException("stall not found"));
        if (!traders.exists(councilId, traderId)) {
            throw new NotFoundException("trader not found");
        }

        switch (stall.status()) {
            case SUSPENDED -> throw new ConflictException("stall " + stall.code() + " is suspended");
            case ALLOCATED -> {
                StallAllocation current = allocations.findActive(councilId, stallId)
                        .orElseThrow(() -> new ConflictException(
                                "stall " + stall.code() + " is marked allocated but has no active allocation"));
                if (current.traderId().equals(traderId)) {
                    return current;
                }
                throw new ConflictException("stall " + stall.code() + " is already allocated to another trader");
            }
            case VACANT -> {
                StallAllocation allocation = new StallAllocation(
                        UUID.randomUUID(), councilId, stallId, traderId, clock.instant(), null);
                allocations.add(allocation);
                stalls.setStatus(councilId, stallId, StallStatus.ALLOCATED);
                return allocation;
            }
        }
        throw new IllegalStateException("unreachable: " + stall.status());
    }
}
