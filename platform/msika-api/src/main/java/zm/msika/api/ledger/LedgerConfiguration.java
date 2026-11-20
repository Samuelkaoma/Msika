package zm.msika.api.ledger;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import zm.msika.api.seal.SealKeys;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.ledger.DayClose;
import zm.msika.core.ledger.LedgerLock;
import zm.msika.core.ledger.LedgerRepository;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.ledger.SealRepository;
import zm.msika.core.stall.StallRepository;
import zm.msika.ledger.EcdsaSignatureScheme;
import zm.msika.ledger.SignatureScheme;

/** Creates the ledger use cases from msika-core, which knows nothing about Spring. */
@Configuration
@EnableScheduling
class LedgerConfiguration {

    @Bean
    SignatureScheme signatureScheme() {
        return new EcdsaSignatureScheme();
    }

    @Bean
    RecordEntry recordEntry(
            CouncilRepository councils,
            StallRepository stalls,
            LedgerRepository ledger,
            SealRepository seals,
            LedgerLock lock) {
        return new RecordEntry(councils, stalls, ledger, seals, lock);
    }

    @Bean
    DayClose dayClose(
            CouncilRepository councils,
            LedgerRepository ledger,
            SealRepository seals,
            LedgerLock lock,
            SignatureScheme scheme,
            SealKeys keys,
            Clock clock) {
        return new DayClose(councils, ledger, seals, lock, scheme, keys.privateKey(), keys.keyId(), clock);
    }
}
