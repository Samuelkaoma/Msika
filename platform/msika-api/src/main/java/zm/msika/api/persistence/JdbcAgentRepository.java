package zm.msika.api.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.agent.Agent;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.phone.PhoneNumber;

/** Keeps agents in Postgres. */
@Repository
class JdbcAgentRepository implements AgentRepository {

    private final JdbcClient jdbc;

    JdbcAgentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(Agent agent) {
        try {
            jdbc.sql("insert into agent (id, council_id, msisdn, display_name, active, created_at) "
                            + "values (:id, :councilId, :msisdn, :name, :active, :createdAt)")
                    .param("id", agent.id())
                    .param("councilId", agent.councilId())
                    .param("msisdn", agent.phone().e164())
                    .param("name", agent.displayName())
                    .param("active", agent.active())
                    .param("createdAt", OffsetDateTime.ofInstant(agent.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("an agent with this phone number is already registered");
        }
    }

    @Override
    public List<Agent> list(UUID councilId) {
        return jdbc.sql("select id, council_id, msisdn, display_name, active, created_at from agent "
                        + "where council_id = :councilId order by display_name, id")
                .param("councilId", councilId)
                .query((rs, row) -> new Agent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("council_id", UUID.class),
                        new PhoneNumber(rs.getString("msisdn")),
                        rs.getString("display_name"),
                        rs.getBoolean("active"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public boolean existsByPhone(UUID councilId, PhoneNumber phone) {
        return jdbc.sql("select count(*) from agent where council_id = :councilId and msisdn = :msisdn")
                .param("councilId", councilId)
                .param("msisdn", phone.e164())
                .query(Integer.class)
                .single() > 0;
    }
}
