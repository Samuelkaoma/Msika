package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.UserRepository;

/**
 * Keeps users in Postgres.
 *
 * <p>Finding a user by email or id goes through two narrow {@code SECURITY DEFINER} functions (see
 * migration V4), because the council is not known until the user has been read. Adding and changing
 * users goes through the table, under row-level security, so it needs a council to be set.
 */
@Repository
class JdbcUserRepository implements UserRepository {

    private static final String COLUMNS = "id, council_id, email, password_hash, role, active, created_at";

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(CouncilUser user) {
        try {
            jdbc.sql("insert into council_user (" + COLUMNS + ") values "
                            + "(:id, :councilId, :email, :passwordHash, :role, :active, :createdAt)")
                    .param("id", user.id())
                    .param("councilId", user.councilId())
                    .param("email", user.email())
                    .param("passwordHash", user.passwordHash())
                    .param("role", user.role().name())
                    .param("active", user.active())
                    .param("createdAt", OffsetDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("a user with this email already exists");
        }
    }

    @Override
    public Optional<CouncilUser> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from auth_find_user_by_email(:email)")
                .param("email", email)
                .query((rs, row) -> map(rs))
                .optional();
    }

    @Override
    public Optional<CouncilUser> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from auth_find_user_by_id(:id)")
                .param("id", id)
                .query((rs, row) -> map(rs))
                .optional();
    }

    @Override
    public Optional<CouncilUser> findInCouncil(UUID councilId, UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from council_user where council_id = :councilId and id = :id")
                .param("councilId", councilId)
                .param("id", userId)
                .query((rs, row) -> map(rs))
                .optional();
    }

    @Override
    public void setActive(UUID councilId, UUID userId, boolean active) {
        jdbc.sql("update council_user set active = :active where council_id = :councilId and id = :id")
                .param("active", active)
                .param("councilId", councilId)
                .param("id", userId)
                .update();
    }

    private static CouncilUser map(ResultSet rs) throws SQLException {
        return new CouncilUser(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
