package zm.msika.api.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
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
 *
 * <p>Every method that changes something writes one audit row in the same transaction, so a change
 * and its record succeed or fail together.
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
    private final TenantContext tenant;
    private final AuditLog audit;

    CouncilAdminService(
            CreateCouncil createCouncil,
            CreateMarket createMarket,
            ImportStalls importStalls,
            AllocateStall allocateStall,
            FreeStall freeStall,
            CouncilRepository councils,
            MarketRepository markets,
            StallRepository stalls,
            TenantContext tenant,
            AuditLog audit) {
        this.createCouncil = createCouncil;
        this.createMarket = createMarket;
        this.importStalls = importStalls;
        this.allocateStall = allocateStall;
        this.freeStall = freeStall;
        this.councils = councils;
        this.markets = markets;
        this.stalls = stalls;
        this.tenant = tenant;
        this.audit = audit;
    }

    Council createCouncil(String name, String timezone) {
        Council council = createCouncil.execute(name, timezone);
        tenant.use(council.id());
        audit.record(council.id(), CurrentUser.actor(), "council.create", council.id().toString());
        return council;
    }

    // Every method below first says which council the transaction is for, so row-level security
    // hides all other councils' rows. The security rules have already checked that the signed-in
    // user may act in the council named in the URL.

    Market createMarket(UUID councilId, String code, String name, String location) {
        tenant.use(councilId);
        Market market = createMarket.execute(councilId, code, name, location);
        audit.record(councilId, CurrentUser.actor(), "market.create", market.code());
        return market;
    }

    @Transactional(readOnly = true)
    List<Market> listMarkets(UUID councilId) {
        tenant.use(councilId);
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        return markets.list(councilId);
    }

    List<Stall> importStalls(UUID councilId, UUID marketId, String csv) {
        tenant.use(councilId);
        List<Stall> created = importStalls.execute(councilId, marketId, csv);
        audit.record(councilId, CurrentUser.actor(), "stalls.import",
                "market " + marketId + ", " + created.size() + " stalls");
        return created;
    }

    @Transactional(readOnly = true)
    List<Stall> listStalls(UUID councilId, UUID marketId) {
        tenant.use(councilId);
        markets.find(councilId, marketId).orElseThrow(() -> new NotFoundException("market not found"));
        return stalls.listByMarket(councilId, marketId);
    }

    StallAllocation allocate(UUID councilId, UUID stallId, UUID traderId) {
        tenant.use(councilId);
        StallAllocation allocation = allocateStall.execute(councilId, stallId, traderId);
        audit.record(councilId, CurrentUser.actor(), "stall.allocate", "stall " + stallId + " to trader " + traderId);
        return allocation;
    }

    void free(UUID councilId, UUID stallId) {
        tenant.use(councilId);
        if (freeStall.execute(councilId, stallId)) {
            audit.record(councilId, CurrentUser.actor(), "stall.free", "stall " + stallId);
        }
    }
}
