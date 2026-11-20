package zm.msika.zamsim.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every few seconds, fails the request-to-pay requests nobody answered in time, and reports each by callback. */
@Component
class RequestExpirySweeper {

    private static final Logger LOG = LoggerFactory.getLogger(RequestExpirySweeper.class);

    private final WalletService wallets;
    private final boolean enabled;

    RequestExpirySweeper(WalletService wallets, @Value("${zamsim.request-expiry.enabled:true}") boolean enabled) {
        this.wallets = wallets;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${zamsim.request-expiry.poll-ms:5000}")
    void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int expired = wallets.expireOverdue();
            if (expired > 0) {
                LOG.info("expired {} request-to-pay request(s)", expired);
            }
        } catch (RuntimeException e) {
            LOG.error("expiring requests failed", e);
        }
    }
}
