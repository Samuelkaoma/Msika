package zm.msika.elevysim.levy;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The elevysim HTTP API (ARCHITECTURE.md section 6). */
@RestController
class LevyController {

    record OpenRequest(String councilRef, String marketRef, String traderRef, Long dailyLevyNgwee) {}

    record TopUpRequest(Long amountNgwee, String reference) {}

    record ChargeRequest(LocalDate date) {}

    private final LevyService levy;

    LevyController(LevyService levy) {
        this.levy = levy;
    }

    @PostMapping("/v1/levy-accounts")
    ResponseEntity<LevyService.Account> open(@RequestBody OpenRequest r) {
        LevyService.Account a = levy.open(r.councilRef(), r.marketRef(), r.traderRef(),
                r.dailyLevyNgwee() == null ? 0 : r.dailyLevyNgwee());
        return ResponseEntity.created(URI.create("/v1/levy-accounts/" + a.id())).body(a);
    }

    /** Find a trader's account at a market, or list a council's accounts. */
    @GetMapping("/v1/levy-accounts")
    List<LevyService.Account> find(@RequestParam(value = "market", required = false) String market,
            @RequestParam(value = "trader", required = false) String trader,
            @RequestParam(value = "council", required = false) String council,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        if (market != null && trader != null) {
            return levy.byTrader(market, trader).map(List::of).orElse(List.of());
        }
        return levy.list(council, limit);
    }

    @GetMapping("/v1/levy-accounts/{id}")
    LevyService.Account account(@PathVariable UUID id) {
        return levy.account(id);
    }

    @GetMapping("/v1/levy-accounts/{id}/movements")
    List<LevyService.Movement> movements(@PathVariable UUID id) {
        return levy.movements(id);
    }

    @PostMapping("/v1/levy-accounts/{id}/top-ups")
    LevyService.Account topUp(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody TopUpRequest r) {
        return levy.topUp(id, key, r.amountNgwee() == null ? 0 : r.amountNgwee(), r.reference());
    }

    /** Charges every account for a day (today in Lusaka if no date is given). Safe to call again. */
    @PostMapping("/v1/admin/run-daily-charge")
    LevyService.ChargeRun charge(@RequestBody(required = false) ChargeRequest r) {
        return levy.chargeAll(r == null || r.date() == null ? levy.today() : r.date());
    }

    @Configuration
    @EnableScheduling
    static class Wiring {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /** The scheduled daily charge, early each morning in Lusaka for the day starting. Off in tests. */
    @Component
    @ConditionalOnProperty(name = "elevysim.daily-charge.enabled", havingValue = "true", matchIfMissing = true)
    static class DailyCharge {
        private static final Logger LOG = LoggerFactory.getLogger(DailyCharge.class);
        private final LevyService levy;

        DailyCharge(LevyService levy) {
            this.levy = levy;
        }

        @Scheduled(cron = "${elevysim.daily-charge.cron:0 0 6 * * *}", zone = "Africa/Lusaka")
        void run() {
            LevyService.ChargeRun run = levy.chargeAll(levy.today());
            LOG.info("daily levy charge for {}: {} charged, {} already charged, {} ngwee", run.date(), run.charged(),
                    run.alreadyCharged(), run.totalNgwee());
        }
    }
}
