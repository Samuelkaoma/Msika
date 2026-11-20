package zm.msika.api.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;

/** Keeps councils in Postgres. */
@Repository
class JdbcCouncilRepository implements CouncilRepository {

    private final JdbcClient jdbc;

    JdbcCouncilRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(Council council) {
        jdbc.sql("insert into council (id, name, timezone, created_at) values (:id, :name, :timezone, :createdAt)")
                .param("id", council.id())
                .param("name", council.name())
                .param("timezone", council.timezone().getId())
                .param("createdAt", OffsetDateTime.ofInstant(council.createdAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<Council> find(UUID id) {
        return jdbc.sql("select id, name, timezone, created_at from council where id = :id")
                .param("id", id)
                .query((rs, row) -> new Council(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        ZoneId.of(rs.getString("timezone")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
