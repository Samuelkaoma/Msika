package zm.msika.core.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.phone.PhoneNumber;

/**
 * A deposit agent: a person who takes a trader's closing-time cash and deposits it into the trader's
 * wallet. Registered by the council. An agent's number can only use agent commands (SMS-PROTOCOL.md).
 *
 * @param id the agent's id
 * @param councilId the council that registered them
 * @param phone their mobile number; printing an agent shows it masked
 * @param displayName the name they are known by, 2 to 100 characters
 * @param active false once the council has stopped them acting as an agent
 * @param createdAt when they were registered
 */
public record Agent(
        UUID id, UUID councilId, PhoneNumber phone, String displayName, boolean active, Instant createdAt) {

    public Agent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        displayName = displayName.trim();
        if (displayName.length() < 2 || displayName.length() > 100) {
            throw new InvalidInputException("name must be 2 to 100 characters");
        }
    }
}
