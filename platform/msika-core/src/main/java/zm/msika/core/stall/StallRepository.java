package zm.msika.core.stall;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Where stalls are kept. Implemented in msika-api. */
public interface StallRepository {

    /** Adds all the stalls or none of them. */
    void addAll(List<Stall> stalls);

    Optional<Stall> find(UUID councilId, UUID stallId);

    List<Stall> listByMarket(UUID councilId, UUID marketId);

    /** Which of these stall codes the council already has. */
    Set<String> existingCodes(UUID councilId, Collection<String> codes);

    void setStatus(UUID councilId, UUID stallId, StallStatus status);
}
