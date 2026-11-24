package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.RoleGrant;
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

    private static final String COLUMNS = "id, council_id, email, password_hash, active, created_at";

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(CouncilUser user, String grantedBy) {
        try {
            jdbc.sql("insert into council_user (" + COLUMNS + ") values "
                            + "(:id, :councilId, :email, :passwordHash, :active, :createdAt)")
                    .param("id", user.id())
                    .param("councilId", user.councilId())
                    .param("email", user.email())
                    .param("passwordHash", user.passwordHash())
                    .param("active", user.active())
                    .param("createdAt", OffsetDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("a user with this email already exists");
        }
        user.grants().forEach(g -> grant(user.councilId(), user.id(), g, grantedBy));
    }

    @Override
    public void grant(UUID councilId, UUID userId, RoleGrant grant, String by) {
        try {
            jdbc.sql("insert into user_role (id, council_id, user_id, role, market_ids, granted_by, granted_at) "
                            + "values (:id, :c, :u, :role, :markets, :by, now())")
                    .param("id", UUID.randomUUID()).param("c", councilId).param("u", userId).param("role", grant.role().name())
                    .param("markets", grant.marketIds().isEmpty() ? null : grant.marketIds().toArray(new UUID[0]), java.sql.Types.ARRAY)
                    .param("by", by).update();
        } catch (DataIntegrityViolationException e) {
            // The database keeps duties apart too (migration V17), and knows which markets are the council's.
            throw new ConflictException("the role cannot be granted: the user holds it or a clashing role, or it names a "
                    + "market of another council");
        }
    }

    @Override
    public boolean revoke(UUID councilId, UUID userId, Role role, String by) {
        return jdbc.sql("update user_role set revoked_at = now(), revoked_by = :by where council_id = :c and user_id = :u "
                        + "and role = :role and revoked_at is null")
                .param("by", by).param("c", councilId).param("u", userId).param("role", role.name()).update() == 1;
    }

    @Override
    public List<CouncilUser> list(UUID councilId) {
        return jdbc.sql("select " + COLUMNS + " from council_user where council_id = :c order by email")
                .param("c", councilId).query((rs, row) -> map(rs)).list();
    }

    private List<RoleGrant> grants(UUID userId, UUID councilId) {
        if (councilId == null) {
            return List.of(RoleGrant.councilWide(Role.MSIKA_OPERATOR));
        }
        return jdbc.sql("select role, market_ids from auth_find_roles(:u)").param("u", userId)
                .query((rs, row) -> new RoleGrant(Role.valueOf(rs.getString("role")), rs.getArray("market_ids") == null ? Set.of()
                        : Set.of((UUID[]) rs.getArray("market_ids").getArray())))
                .list();
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

    private CouncilUser map(ResultSet rs) throws SQLException {
        return new CouncilUser(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                grants(rs.getObject("id", UUID.class), rs.getObject("council_id", UUID.class)),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
