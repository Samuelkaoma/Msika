package zm.msika.core.plate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.market.Market;
import zm.msika.core.market.MarketRepository;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallRepository;

/** Use case: one PDF with a plate for every stall in a market, ordered by stall code. */
public final class GenerateMarketPlates {

    private final MarketRepository markets;
    private final StallRepository stalls;
    private final PlateRenderer renderer;

    public GenerateMarketPlates(MarketRepository markets, StallRepository stalls, PlateRenderer renderer) {
        this.markets = Objects.requireNonNull(markets);
        this.stalls = Objects.requireNonNull(stalls);
        this.renderer = Objects.requireNonNull(renderer);
    }

    /**
     * @throws NotFoundException if the market is not in this council
     * @throws ConflictException if the market has no stalls yet
     */
    public byte[] execute(UUID councilId, UUID marketId) {
        Market market = markets.find(councilId, marketId)
                .orElseThrow(() -> new NotFoundException("market not found"));
        List<Stall> inMarket = stalls.listByMarket(councilId, marketId);
        if (inMarket.isEmpty()) {
            throw new ConflictException("the market has no stalls yet, so there are no plates to print");
        }
        List<StallPlate> plates = inMarket.stream()
                .map(s -> new StallPlate(s.code(), market.name(), s.qrToken()))
                .toList();
        return renderer.render(plates);
    }
}
