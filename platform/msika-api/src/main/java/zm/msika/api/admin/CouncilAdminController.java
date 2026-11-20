package zm.msika.api.admin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.core.council.Council;
import zm.msika.core.market.Market;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallAllocation;

/**
 * REST endpoints for operators and council admins: councils, markets, stalls and allocations.
 *
 * <p><strong>Not secured yet.</strong> Sign-in, roles and "default deny" arrive in card M-09, and the
 * database-level separation of councils in M-08. Until then these endpoints must not be exposed.
 */
@RestController
class CouncilAdminController {

    private final CouncilAdminService service;

    CouncilAdminController(CouncilAdminService service) {
        this.service = service;
    }

    record CreateCouncilRequest(String name, String timezone) {}

    record CouncilResponse(UUID id, String name, String timezone) {
        static CouncilResponse of(Council c) {
            return new CouncilResponse(c.id(), c.name(), c.timezone().getId());
        }
    }

    record CreateMarketRequest(String code, String name, String location) {}

    record MarketResponse(UUID id, UUID councilId, String code, String name, String location, String dayCloseTime) {
        static MarketResponse of(Market m) {
            return new MarketResponse(
                    m.id(), m.councilId(), m.code(), m.name(), m.location(), m.dayCloseTime().toString());
        }
    }

    record ImportResponse(int created) {}

    record StallResponse(UUID id, UUID marketId, String code, String stallClass, String status, String qrToken) {
        static StallResponse of(Stall s) {
            return new StallResponse(s.id(), s.marketId(), s.code(), s.stallClass(), s.status().name(), s.qrToken());
        }
    }

    record AllocateRequest(UUID traderId) {}

    record AllocationResponse(UUID id, UUID stallId, UUID traderId, String validFrom) {
        static AllocationResponse of(StallAllocation a) {
            return new AllocationResponse(a.id(), a.stallId(), a.traderId(), a.validFrom().toString());
        }
    }

    @PostMapping("/api/v1/councils")
    ResponseEntity<CouncilResponse> createCouncil(@RequestBody CreateCouncilRequest request) {
        Council council = service.createCouncil(request.name(), request.timezone());
        return ResponseEntity.created(URI.create("/api/v1/councils/" + council.id()))
                .body(CouncilResponse.of(council));
    }

    @PostMapping("/api/v1/councils/{councilId}/markets")
    ResponseEntity<MarketResponse> createMarket(
            @PathVariable UUID councilId, @RequestBody CreateMarketRequest request) {
        Market market = service.createMarket(councilId, request.code(), request.name(), request.location());
        return ResponseEntity.created(URI.create("/api/v1/councils/" + councilId + "/markets/" + market.id()))
                .body(MarketResponse.of(market));
    }

    @GetMapping("/api/v1/councils/{councilId}/markets")
    List<MarketResponse> listMarkets(@PathVariable UUID councilId) {
        return service.listMarkets(councilId).stream().map(MarketResponse::of).toList();
    }

    /** Body is the CSV file itself (Content-Type text/csv), read as UTF-8. */
    @PostMapping("/api/v1/councils/{councilId}/markets/{marketId}/stalls/import")
    ResponseEntity<ImportResponse> importStalls(
            @PathVariable UUID councilId, @PathVariable UUID marketId, @RequestBody byte[] csv) {
        List<Stall> created = service.importStalls(councilId, marketId, new String(csv, StandardCharsets.UTF_8));
        return ResponseEntity.status(201).body(new ImportResponse(created.size()));
    }

    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/stalls")
    List<StallResponse> listStalls(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        return service.listStalls(councilId, marketId).stream().map(StallResponse::of).toList();
    }

    /** PUT, because doing it twice has the same effect as doing it once. */
    @PutMapping("/api/v1/councils/{councilId}/stalls/{stallId}/allocation")
    AllocationResponse allocate(
            @PathVariable UUID councilId, @PathVariable UUID stallId, @RequestBody AllocateRequest request) {
        return AllocationResponse.of(service.allocate(councilId, stallId, request.traderId()));
    }

    /** Also safe to repeat: freeing a stall that is already free answers 204 again. */
    @DeleteMapping("/api/v1/councils/{councilId}/stalls/{stallId}/allocation")
    ResponseEntity<Void> free(@PathVariable UUID councilId, @PathVariable UUID stallId) {
        service.free(councilId, stallId);
        return ResponseEntity.noContent().build();
    }
}
