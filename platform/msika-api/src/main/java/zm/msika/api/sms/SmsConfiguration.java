package zm.msika.api.sms;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zm.msika.api.payments.CallbackVerifier;

/**
 * Wires the SMS channel. Settings come from the environment, never from the repository:
 *
 * <ul>
 *   <li>{@code msika.sms.gateway-secret}: the secret the gateway signs inbound texts with. Empty means every
 *       inbound text is refused (fail closed).
 *   <li>{@code msika.sms.gateway}: {@code console} (the default, for development and demos).
 * </ul>
 */
@Configuration
class SmsConfiguration {

    /** Inbound texts are signed exactly like payment callbacks: HMAC-SHA256 over the timestamp, a dot and the body. */
    @Bean
    CallbackVerifier smsGatewayVerifier(@Value("${msika.sms.gateway-secret:}") String secret,
            @Value("${msika.sms.timestamp-tolerance:PT5M}") Duration tolerance, Clock clock) {
        return new CallbackVerifier(secret, clock, tolerance);
    }

    /** A few threads that process texts after the webhook has answered. */
    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService smsProcessingExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "sms-processing");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConditionalOnProperty(name = "msika.sms.gateway", havingValue = "console", matchIfMissing = true)
    ConsoleSmsGateway consoleSmsGateway(Clock clock) {
        return new ConsoleSmsGateway(clock);
    }

    /** The schedules: send the outbox every couple of seconds, and pick up any text left unprocessed. */
    @Component
    @ConditionalOnProperty(name = "msika.sms.workers.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedules {
        private final OutboxWorker outbox;
        private final SmsProcessor processor;

        Schedules(OutboxWorker outbox, SmsProcessor processor) {
            this.outbox = outbox;
            this.processor = processor;
        }

        @Scheduled(fixedDelayString = "${msika.sms.outbox.every:PT2S}")
        void sendOutbox() {
            outbox.sendDue();
        }

        @Scheduled(fixedDelayString = "${msika.sms.sweep.every:PT30S}")
        void sweepInbound() {
            processor.sweep(Duration.ofSeconds(15));
        }
    }
}
