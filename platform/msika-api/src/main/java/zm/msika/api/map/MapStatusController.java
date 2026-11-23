package zm.msika.api.map;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.security.CurrentUser;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.map.StallState;
import zm.msika.core.user.Role;

/** The map's live data (card M-49): status per stall, the stall panel, and search. Reads, for every user of the council. */
@RestController
class MapStatusController {

    private final MapStatusService status;
    private final CouncilRepository councils;
    private final Clock clock;

    MapStatusController(MapStatusService status, CouncilRepository councils, Clock clock) {
        this.status = status;
        this.councils = councils;
        this.clock = clock;
    }

    /** Officers read only, so they see phone numbers masked; admins and operators see them in full. */
    private static boolean fullPhones() {
        return CurrentUser.require().role() != Role.COUNCIL_OFFICER;
    }

    private LocalDate today(UUID councilId) {
        return clock.instant().atZone(councils.find(councilId).orElseThrow().timezone()).toLocalDate();
    }

    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/map-status")
    MapStatusService.MarketStatus mapStatus(@PathVariable UUID councilId, @PathVariable UUID marketId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return status.status(councilId, marketId, date == null ? today(councilId) : date, StallState.DEFAULT_QUIET_DAYS,
                fullPhones());
    }

    @GetMapping("/api/v1/councils/{councilId}/stalls/{stallId}/panel")
    MapStatusService.Panel panel(@PathVariable UUID councilId, @PathVariable UUID stallId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return status.panel(councilId, stallId, date == null ? today(councilId) : date, fullPhones());
    }

    @GetMapping("/api/v1/councils/{councilId}/search")
    List<MapStatusService.Hit> search(@PathVariable UUID councilId, @RequestParam("q") String q) {
        return status.search(councilId, q, fullPhones());
    }
}
