package zm.msika.api.levy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.security.CurrentUser;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.levy.LevyRule;

/** Levy rules and charges for a council (card M-23). Reading is open to the council's users; changing needs an admin. */
@RestController
class LevyController {

    record RuleRequest(String stallClass, Long amountNgwee, LocalDate validFrom) {}

    record RunRequest(LocalDate date) {}

    private final LevyChargeService levy;

    LevyController(LevyChargeService levy) {
        this.levy = levy;
    }

    @PostMapping("/api/v1/councils/{councilId}/markets/{marketId}/levy-rules")
    @ResponseStatus(HttpStatus.CREATED)
    LevyRule addRule(@PathVariable UUID councilId, @PathVariable UUID marketId, @RequestBody RuleRequest r) {
        return levy.addRule(councilId, marketId, r.stallClass(), r.amountNgwee() == null ? 0 : r.amountNgwee(),
                r.validFrom(), CurrentUser.actor());
    }

    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/levy-rules")
    List<LevyRule> rules(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        return levy.rules(councilId, marketId);
    }

    /** Runs the day's charge by hand (today in the council's time zone if no date). Safe to repeat. */
    @PostMapping("/api/v1/councils/{councilId}/levy-charges/run")
    LevyChargeService.Run run(@PathVariable UUID councilId, @RequestBody(required = false) RunRequest r) {
        LocalDate day = r == null || r.date() == null ? levy.today(councilId) : r.date();
        return levy.charge(councilId, day, CurrentUser.actor());
    }

    @GetMapping("/api/v1/councils/{councilId}/levy-charges")
    List<LevyChargeService.ChargeRow> charges(@PathVariable UUID councilId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return levy.charges(councilId, date);
    }

    /**
     * The scheduled charge: early each morning, for each council, today and the two days before (in case the
     * server was down). Already-charged days change nothing.
     */
    @Component
    @ConditionalOnProperty(name = "msika.levy-charge.enabled", havingValue = "true", matchIfMissing = true)
    static class DailyLevyCharge {
        static final String ACTOR = "system:levy-charge";
        private static final Logger LOG = LoggerFactory.getLogger(DailyLevyCharge.class);
        private final CouncilRepository councils;
        private final LevyChargeService levy;

        DailyLevyCharge(CouncilRepository councils, LevyChargeService levy) {
            this.councils = councils;
            this.levy = levy;
        }

        @Scheduled(cron = "${msika.levy-charge.cron:0 15 5 * * *}", zone = "Africa/Lusaka")
        void run() {
            for (UUID councilId : councils.listIds()) {
                try {
                    LocalDate today = levy.today(councilId);
                    for (int back = 2; back >= 0; back--) {
                        LevyChargeService.Run run = levy.charge(councilId, today.minusDays(back), ACTOR);
                        if (!run.stallsWithoutRule().isEmpty()) {
                            LOG.warn("council {}: {} held stall(s) have no levy rule on {}", councilId,
                                    run.stallsWithoutRule().size(), run.date());
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.error("levy charge failed for council {}", councilId, e);
                }
            }
        }
    }
}
