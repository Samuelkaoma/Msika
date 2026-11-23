package zm.msika.api.report;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard's figures (card M-27). All reads, so every user of the council may see them, officers included. */
@RestController
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/api/v1/councils/{councilId}/reports/markets")
    List<ReportService.MarketOccupancy> markets(@PathVariable UUID councilId,
            @RequestParam("date") @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return reports.markets(councilId, date);
    }

    @GetMapping("/api/v1/councils/{councilId}/reports/collections")
    List<ReportService.DayCollections> collections(@PathVariable UUID councilId,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.collections(councilId, from, to);
    }

    @GetMapping("/api/v1/councils/{councilId}/reports/arrears")
    List<ReportService.Arrears> arrears(@PathVariable UUID councilId) {
        return reports.arrears(councilId);
    }

    @GetMapping("/api/v1/councils/{councilId}/reports/agents")
    List<ReportService.AgentActivity> agents(@PathVariable UUID councilId,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.agents(councilId, from, to);
    }

    @GetMapping("/api/v1/councils/{councilId}/reports/reconciliation")
    List<ReportService.ReconciliationDay> reconciliation(@PathVariable UUID councilId,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.reconciliation(councilId, from, to);
    }

    @GetMapping("/api/v1/councils/{councilId}/reports/seals")
    List<ReportService.Seal> seals(@PathVariable UUID councilId,
            @RequestParam(value = "limit", defaultValue = "60") int limit) {
        return reports.seals(councilId, limit);
    }

    /** A read, so a GET: officers may check the seals too. */
    @GetMapping("/api/v1/councils/{councilId}/reports/seals/verification")
    ReportService.SealCheck verifySeals(@PathVariable UUID councilId) {
        return reports.verifySeals(councilId);
    }
}
