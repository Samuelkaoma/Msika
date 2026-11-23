package zm.msika.api.levy;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import zm.msika.core.levy.LevyAuthorityPort;
import zm.msika.core.levy.LevyAuthorityUnavailableException;

/**
 * Wires the levy authority port. {@code msika.levy.elevysim.base-url} says where elevysim is; empty means no levy
 * authority is configured, and every call to it fails (so sweeps wait in {@code MOVED} until one is).
 */
@Configuration
class LevyConfiguration {

    @Bean
    LevyAuthorityPort levyAuthorityPort(@Value("${msika.levy.elevysim.base-url:}") String baseUrl) {
        if (baseUrl.isBlank()) {
            return new LevyAuthorityPort() {
                @Override
                public String openAccount(String c, String m, String t, long daily) {
                    throw notConfigured();
                }

                @Override
                public void topUp(String accountId, String key, long amount, String reference) {
                    throw notConfigured();
                }

                @Override
                public Optional<LevyAccount> account(String accountId) {
                    throw notConfigured();
                }

                private LevyAuthorityUnavailableException notConfigured() {
                    return new LevyAuthorityUnavailableException(
                            "no levy authority is configured (msika.levy.elevysim.base-url)", null);
                }
            };
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new ElevysimLevyAdapter(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build());
    }

    /** Moves the levy sweeps on every few seconds. */
    @Component
    @ConditionalOnProperty(name = "msika.levy-sweep.enabled", havingValue = "true", matchIfMissing = true)
    static class SweepSchedule {
        private final LevySweep sweep;

        SweepSchedule(LevySweep sweep) {
            this.sweep = sweep;
        }

        @Scheduled(fixedDelayString = "${msika.levy-sweep.every:PT5S}")
        void run() {
            sweep.runOnce();
        }
    }
}
