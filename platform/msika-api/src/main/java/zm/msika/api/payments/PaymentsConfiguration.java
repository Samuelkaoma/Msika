package zm.msika.api.payments;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.payments.HandlePaymentEvent;
import zm.msika.core.payments.PaymentOutcomes;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.RecordWalletPayment;
import zm.msika.core.wallet.WalletRegistry;

/**
 * Wires the payments port to its adapter. Settings come from the environment, never from the repository:
 *
 * <ul>
 *   <li>{@code msika.payments.zamsim.base-url}: where zamsim is. Empty means no payments system is configured.
 *   <li>{@code msika.payments.callback-secret}: the shared secret callbacks are signed with. Empty means every
 *       callback is rejected.
 * </ul>
 */
@Configuration
class PaymentsConfiguration {

    @Bean
    PaymentsPort paymentsPort(@Value("${msika.payments.zamsim.base-url:}") String baseUrl) {
        if (baseUrl.isBlank()) {
            return (key, agent, customer, amount, reference) -> {
                throw new zm.msika.core.payments.PaymentsUnavailableException(
                        "no payments system is configured (msika.payments.zamsim.base-url)");
            };
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        return new ZamsimPaymentsAdapter(client);
    }

    @Bean
    CallbackVerifier callbackVerifier(
            @Value("${msika.payments.callback-secret:}") String secret,
            @Value("${msika.payments.callback-tolerance:PT5M}") Duration tolerance,
            Clock clock) {
        return new CallbackVerifier(secret, clock, tolerance);
    }

    @Bean
    HandlePaymentEvent handlePaymentEvent(PaymentRequestRepository requests, RecordEntry recordEntry, Clock clock,
            PaymentOutcomes outcomes) {
        return new HandlePaymentEvent(requests, recordEntry, clock, outcomes);
    }

    @Bean
    RecordWalletPayment recordWalletPayment(WalletRegistry wallets, RecordEntry recordEntry) {
        return new RecordWalletPayment(wallets, recordEntry);
    }
}
