package zm.msika.core.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zm.msika.core.phone.PhoneNumber;

/** Where agents are kept. Implemented in msika-api. */
public interface AgentRepository {

    void add(Agent agent);

    List<Agent> list(UUID councilId);

    Optional<Agent> find(UUID councilId, UUID agentId);

    boolean existsByPhone(UUID councilId, PhoneNumber phone);
}
