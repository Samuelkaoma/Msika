package zm.msika.api.reconciliation;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;

/**
 * The nightly reconciliation (card M-16). Once a night, for each council, it reconciles the last few days in the
 * council's own time zone (yesterday, and by default the day before as well, so a gap that a late callback has
 * since filled disappears from the report).
 *
 * <p>A failure for one council (the payments system down, say) is logged and does not stop the others; the
 * previous report for that day is left as it was.
 */
@Component
@ConditionalOnProperty(name = "msika.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationJob {

    static final String ACTOR = "system:reconciliation";

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationJob.class);

    private final CouncilRepository councils;
    private final ReconciliationService reconciliation;
    private final Clock clock;
    private final int daysBack;

    ReconciliationJob(CouncilRepository councils, ReconciliationService reconciliation, Clock clock,
            @Value("${msika.reconciliation.days-back:2}") int daysBack) {
        this.councils = councils;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.daysBack = Math.max(1, daysBack);
    }

    @Scheduled(cron = "${msika.reconciliation.cron:0 30 2 * * *}", zone = "Africa/Lusaka")
    void run() {
        for (UUID councilId : councils.listIds()) {
            try {
                reconcileFor(councilId);
            } catch (RuntimeException e) {
                LOG.error("reconciliation failed for council {}", councilId, e);
            }
        }
    }

    void reconcileFor(UUID councilId) {
        Council council = councils.find(councilId).orElse(null);
        if (council == null) {
            return;
        }
        LocalDate today = clock.instant().atZone(council.timezone()).toLocalDate();
        for (int back = daysBack; back >= 1; back--) {
            var report = reconciliation.reconcile(councilId, today.minusDays(back), ACTOR);
            if (!report.differences().isEmpty()) {
                LOG.warn("reconciliation found {} difference(s) for council {} on {}",
                        report.differences().size(), councilId, report.businessDate());
            }
        }
    }
}
