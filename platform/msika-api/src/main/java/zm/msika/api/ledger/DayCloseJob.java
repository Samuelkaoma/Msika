package zm.msika.api.ledger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * The scheduled day close (ARCHITECTURE.md section 4.4). It wakes up every few minutes and, for each
 * council, closes every day that is finished in the council's own time zone: yesterday and earlier
 * before the close time, and today as well once the close time (default 20:00) has passed.
 *
 * <p>It is safe to run often and from more than one server: closing a day that is already sealed does
 * nothing, and the ledger lock makes two servers take turns. A failure for one council is logged and does
 * not stop the others.
 */
@Component
@ConditionalOnProperty(name = "msika.day-close.enabled", havingValue = "true", matchIfMissing = true)
class DayCloseJob {

    static final String ACTOR = "system:day-close";

    private static final Logger LOG = LoggerFactory.getLogger(DayCloseJob.class);

    private final CouncilRepository councils;
    private final LedgerService ledger;
    private final Clock clock;
    private final LocalTime closeTime;

    DayCloseJob(
            CouncilRepository councils,
            LedgerService ledger,
            Clock clock,
            @Value("${msika.day-close.time:20:00}") LocalTime closeTime) {
        this.councils = councils;
        this.ledger = ledger;
        this.clock = clock;
        this.closeTime = closeTime;
    }

    @Scheduled(cron = "${msika.day-close.cron:0 */5 * * * *}")
    void run() {
        for (UUID councilId : councils.listIds()) {
            try {
                closeFor(councilId);
            } catch (RuntimeException e) {
                LOG.error("day close failed for council {}", councilId, e);
            }
        }
    }

    /** Closes what is due for one council. Public to the package so a test can run it without waiting. */
    void closeFor(UUID councilId) {
        Council council = councils.find(councilId).orElse(null);
        if (council == null) {
            return;
        }
        var now = clock.instant().atZone(council.timezone());
        LocalDate through = now.toLocalTime().isBefore(closeTime) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        int sealed = ledger.closeDays(councilId, through, ACTOR).size();
        if (sealed > 0) {
            LOG.info("closed {} day(s) for council {} through {}", sealed, councilId, through);
        }
    }
}
