package zm.msika.core.agent;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.phone.PhoneNumber;

/** Use case: a council registers a deposit agent. The number is normalised like a trader's. */
public final class RegisterAgent {

    private final CouncilRepository councils;
    private final AgentRepository agents;
    private final Clock clock;

    public RegisterAgent(CouncilRepository councils, AgentRepository agents, Clock clock) {
        this.councils = Objects.requireNonNull(councils);
        this.agents = Objects.requireNonNull(agents);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @throws NotFoundException if the council does not exist
     * @throws ConflictException if an agent with this number is already registered in the council
     */
    public Agent execute(UUID councilId, String phone, String displayName) {
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        PhoneNumber number = PhoneNumber.parse(phone);
        Agent agent = new Agent(
                UUID.randomUUID(), councilId, number, displayName == null ? "" : displayName, true, clock.instant());
        if (agents.existsByPhone(councilId, number)) {
            throw new ConflictException("an agent with this phone number is already registered");
        }
        agents.add(agent);
        return agent;
    }
}
