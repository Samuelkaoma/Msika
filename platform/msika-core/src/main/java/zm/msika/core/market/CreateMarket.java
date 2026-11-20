package zm.msika.core.market;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;

/** Use case: a council admin creates a market. */
public final class CreateMarket {

    private final CouncilRepository councils;
    private final MarketRepository markets;
    private final Clock clock;

    public CreateMarket(CouncilRepository councils, MarketRepository markets, Clock clock) {
        this.councils = Objects.requireNonNull(councils);
        this.markets = Objects.requireNonNull(markets);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @throws NotFoundException if the council does not exist
     * @throws ConflictException if the council already has a market with this code
     */
    public Market execute(UUID councilId, String code, String name, String location) {
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        Market market = new Market(
                UUID.randomUUID(),
                councilId,
                code == null ? "" : code,
                name == null ? "" : name,
                location,
                Market.DEFAULT_DAY_CLOSE_TIME,
                clock.instant());
        if (markets.existsByCode(councilId, market.code())) {
            throw new ConflictException("a market with code " + market.code() + " already exists");
        }
        markets.add(market);
        return market;
    }
}
