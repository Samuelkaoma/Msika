package zm.msika.core.ledger;

import java.time.Instant;
import java.util.Objects;
import zm.msika.ledger.DailySeal;

/**
 * A stored seal: the signed {@link DailySeal} plus which key signed it and when.
 *
 * @param seal the signed commitment to the day
 * @param keyId which signing key made it, so a verifier knows which public key to use
 * @param sealedAt when the day was closed
 */
public record SealedDay(DailySeal seal, String keyId, Instant sealedAt) {

    public SealedDay {
        Objects.requireNonNull(seal, "seal must not be null");
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(sealedAt, "sealedAt must not be null");
    }
}
