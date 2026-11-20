package zm.msika.zamsim.callback;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where and how zamsim sends callbacks. With no {@code zamsim.callback.url} callbacks are switched off
 * and results are only available by asking for them (GET /v1/transactions/{id}).
 *
 * <p>The signing secret is never in the repository: set {@code zamsim.callback.secret} from the
 * environment (ZAMSIM_CALLBACK_SECRET). It must be the same secret Msika checks with.
 */
@Component
public class CallbackConfig {

    private final String url;
    private final CallbackSigner signer;
    private final int maxAttempts;
    private final List<Long> backoffMs;
    private final int timeoutMs;
    private final boolean workerEnabled;

    CallbackConfig(
            @Value("${zamsim.callback.url:}") String url,
            @Value("${zamsim.callback.secret:}") String secret,
            @Value("${zamsim.callback.max-attempts:8}") int maxAttempts,
            @Value("${zamsim.callback.backoff-ms:1000,2000,4000,8000,16000,32000,60000}") String backoffMs,
            @Value("${zamsim.callback.timeout-ms:5000}") int timeoutMs,
            @Value("${zamsim.callback.worker.enabled:true}") boolean workerEnabled) {
        this.url = url.trim();
        if (!this.url.isEmpty() && secret.isBlank()) {
            throw new IllegalStateException("zamsim.callback.url is set, so zamsim.callback.secret must be set too");
        }
        this.signer = this.url.isEmpty() ? null : new CallbackSigner(secret);
        this.maxAttempts = maxAttempts;
        this.backoffMs = Arrays.stream(backoffMs.split(",")).map(String::trim).map(Long::parseLong).toList();
        this.timeoutMs = timeoutMs;
        this.workerEnabled = workerEnabled;
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    String url() {
        return url;
    }

    CallbackSigner signer() {
        return signer;
    }

    int maxAttempts() {
        return maxAttempts;
    }

    /** How long to wait after attempt number {@code attempt} (1 = the first) fails. The last value repeats. */
    long backoffAfterAttempt(int attempt) {
        return backoffMs.get(Math.min(attempt - 1, backoffMs.size() - 1));
    }

    int timeoutMs() {
        return timeoutMs;
    }

    boolean workerEnabled() {
        return workerEnabled;
    }
}
