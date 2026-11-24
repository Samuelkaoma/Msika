package zm.msika.core.user;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory stand-ins for the user repository and the password hasher. */
final class UserFakes {

    private UserFakes() {}

    /** Not a real hash. It is readable on purpose so tests can see what was hashed. It counts its checks. */
    static final class Hasher implements PasswordHasher {
        int matchChecks;

        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String hash) {
            matchChecks++;
            return hash.equals("hashed:" + rawPassword);
        }
    }

    static final class Users implements UserRepository {
        final Map<UUID, CouncilUser> byId = new HashMap<>();

        @Override
        public void add(CouncilUser user, String grantedBy) {
            byId.put(user.id(), user);
        }

        @Override
        public void grant(UUID councilId, UUID userId, RoleGrant grant, String by) {
            byId.computeIfPresent(userId, (id, u) -> with(u, java.util.stream.Stream.concat(u.grants().stream(),
                    java.util.stream.Stream.of(grant)).toList()));
        }

        @Override
        public boolean revoke(UUID councilId, UUID userId, Role role, String by) {
            boolean held = byId.get(userId).holds(role);
            byId.computeIfPresent(userId, (id, u) -> with(u, u.grants().stream().filter(g -> g.role() != role).toList()));
            return held;
        }

        @Override
        public java.util.List<CouncilUser> list(UUID councilId) {
            return byId.values().stream().filter(u -> councilId.equals(u.councilId())).toList();
        }

        private static CouncilUser with(CouncilUser u, java.util.List<RoleGrant> grants) {
            return new CouncilUser(u.id(), u.councilId(), u.email(), u.passwordHash(), grants, u.active(), u.createdAt());
        }

        @Override
        public Optional<CouncilUser> findByEmail(String email) {
            return byId.values().stream().filter(u -> u.email().equals(email.toLowerCase(Locale.ROOT))).findFirst();
        }

        @Override
        public Optional<CouncilUser> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<CouncilUser> findInCouncil(UUID councilId, UUID userId) {
            return findById(userId).filter(u -> councilId.equals(u.councilId()));
        }

        @Override
        public void setActive(UUID councilId, UUID userId, boolean active) {
            byId.computeIfPresent(userId, (id, u) -> u.withActive(active));
        }
    }
}
