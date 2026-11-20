package zm.msika.core.trader;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.phone.Language;
import zm.msika.core.phone.PhoneNumber;

/**
 * A market trader. We hold only what is needed (a phone number, a name, a language and the consents):
 * no NRC number, no address (ARCHITECTURE.md rule 8).
 *
 * @param id the trader's id
 * @param councilId the council that registered them
 * @param phone their mobile number; printing a trader shows it masked
 * @param displayName the name they are known by at the market, 2 to 100 characters
 * @param language the language they get messages in
 * @param consentLevySweepAt when they agreed to have levy paid from their deposits, or {@code null}
 * @param consentDataAt when they agreed to us holding their data, or {@code null}
 * @param createdAt when they were registered
 */
public record Trader(
        UUID id,
        UUID councilId,
        PhoneNumber phone,
        String displayName,
        Language language,
        Instant consentLevySweepAt,
        Instant consentDataAt,
        Instant createdAt) {

    public Trader {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        displayName = displayName.trim();
        if (displayName.length() < 2 || displayName.length() > 100) {
            throw new InvalidInputException("name must be 2 to 100 characters");
        }
    }
}
