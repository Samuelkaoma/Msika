package zm.msika.core.market;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where markets are kept. Implemented in msika-api. */
public interface MarketRepository {

    void add(Market market);

    Optional<Market> find(UUID councilId, UUID marketId);

    List<Market> list(UUID councilId);

    boolean existsByCode(UUID councilId, String code);
}
