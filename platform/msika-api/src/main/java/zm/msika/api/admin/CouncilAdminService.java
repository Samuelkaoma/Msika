package zm.msika.api.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.council.CreateCouncil;
import zm.msika.core.market.CreateMarket;
import zm.msika.core.market.Market;
import zm.msika.core.market.MarketRepository;
import zm.msika.core.stall.AllocateStall;
import zm.msika.core.stall.FreeStall;
import zm.msika.core.stall.ImportStalls;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallAllocation;
import zm.msika.core.stall.StallRepository;

/**
 * Runs each msika-core use case inside one database transaction, so a use case either does all its
 * writes or none (a stall import that fails half way leaves nothing behind).
 */
@Service
@Transactional
class CouncilAdminService {

    private final CreateCouncil createCouncil;
    private final CreateMarket createMarket;
    private final ImportStalls importStalls;
    private final AllocateStall allocateStall;
    private final FreeStall freeStall;
    private final CouncilRepository councils;
    private final MarketRepository markets;
    private final StallRepository stalls;

    CouncilAdminService(
            CreateCouncil createCouncil,
            CreateMarket createMarket,
            ImportStalls importStalls,
            AllocateStall allocateStall,
            FreeStall freeStall,
            CouncilRepository councils,
            MarketRepository markets,
            StallRepository stalls) {
        this.createCouncil = createCouncil;
        this.createMarket = createMarket;
        this.importStalls = importStalls;
        this.allocateStall = allocateStall;
        this.freeStall = freeStall;
        this.councils = councils;
        this.markets = markets;
        this.stalls = stalls;
    }

    Council createCouncil(String name, String timezone) {
        return createCouncil.execute(name, timezone);
    }

    Market createMarket(UUID councilId, String code, String name, String location) {
        return createMarket.execute(councilId, code, name, location);
    }

    @Transactional(readOnly = true)
    List<Market> listMarkets(UUID councilId) {
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        return markets.list(councilId);
    }

    List<Stall> importStalls(UUID councilId, UUID marketId, String csv) {
        return importStalls.execute(councilId, marketId, csv);
    }

    @Transactional(readOnly = true)
    List<Stall> listStalls(UUID councilId, UUID marketId) {
        markets.find(councilId, marketId).orElseThrow(() -> new NotFoundException("market not found"));
        return stalls.listByMarket(councilId, marketId);
    }

    StallAllocation allocate(UUID councilId, UUID stallId, UUID traderId) {
        return allocateStall.execute(councilId, stallId, traderId);
    }

    void free(UUID councilId, UUID stallId) {
        freeStall.execute(councilId, stallId);
    }
}
