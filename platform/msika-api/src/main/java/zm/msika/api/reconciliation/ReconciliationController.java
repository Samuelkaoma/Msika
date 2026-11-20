package zm.msika.api.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.security.CurrentUser;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.reconciliation.Difference;

/**
 * The reconciliation report for a council's day (card M-16). Any user of the council can read it; a council admin
 * or an operator can run it again by hand (an officer is read-only).
 */
@RestController
class ReconciliationController {

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    record DifferenceBody(String kind, String paymentsTransactionId, String entryKind, UUID stallId,
            Long ledgerEntryId, Long msikaAmountNgwee, Long paymentsAmountNgwee) {

        static DifferenceBody of(Difference d) {
            return new DifferenceBody(d.kind().name(), d.paymentsTransactionId(), d.entryKind().name(), d.stallId(),
                    d.ledgerEntryId(), d.msikaAmountNgwee(), d.paymentsAmountNgwee());
        }
    }

    record ReportBody(UUID councilId, LocalDate businessDate, Instant ranAt, String ranBy, int paymentsCount,
            int msikaCount, List<DifferenceBody> differences) {

        static ReportBody of(ReconciliationService.Report r) {
            return new ReportBody(r.councilId(), r.businessDate(), r.ranAt(), r.ranBy(), r.paymentsCount(),
                    r.msikaCount(), r.differences().stream().map(DifferenceBody::of).toList());
        }
    }

    @GetMapping("/api/v1/councils/{councilId}/reconciliation/{date}")
    ReportBody report(@PathVariable UUID councilId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.report(councilId, date).map(ReportBody::of)
                .orElseThrow(() -> new NotFoundException("the reconciliation has not run for " + date));
    }

    @PostMapping("/api/v1/councils/{councilId}/reconciliation/{date}")
    ReportBody run(@PathVariable UUID councilId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ReportBody.of(reconciliation.reconcile(councilId, date, CurrentUser.actor()));
    }
}
