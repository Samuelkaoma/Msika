package zm.msika.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;

/** The permission matrix and the rules for changing roles (card M-61). */
class PermissionsTest {

    private final UUID marketA = UUID.randomUUID();
    private final UUID marketB = UUID.randomUUID();
    private final UUID council = UUID.randomUUID();
    private final UserFakes.Users users = new UserFakes.Users();
    private final ChangeRoles change = new ChangeRoles(users);

    private UUID user(RoleGrant grant) {
        UUID id = UUID.randomUUID();
        users.add(new CouncilUser(id, council, id + "@example.test", "x", List.of(grant), true, java.time.Instant.EPOCH), "test");
        return id;
    }

    @Test
    @DisplayName("every role is in the matrix, and read-only roles get nothing that changes data")
    void matrix() {
        for (Role r : Role.values()) {
            assertThat(Permissions.of(r)).as(r.name()).contains(Permission.READ);
        }
        for (Role r : EnumSet.of(Role.AUDITOR, Role.COUNCIL_OFFICER, Role.REVENUE_OFFICER, Role.ENFORCEMENT_OFFICER)) {
            assertThat(Permissions.of(r)).isSubsetOf(Permission.READ, Permission.READ_AUDIT);
        }
        assertThat(Permissions.of(Role.COUNCIL_ADMIN)).doesNotContain(Permission.CREATE_COUNCILS);
    }

    @Test
    @DisplayName("a scoped role changes things only in its markets, and never where no market is known")
    void scope() {
        List<RoleGrant> master = List.of(new RoleGrant(Role.MARKET_MASTER, Set.of(marketA)));

        assertThat(Permissions.allows(master, Permission.ALLOCATE_STALLS, marketA)).isTrue();
        assertThat(Permissions.allows(master, Permission.ALLOCATE_STALLS, marketB)).isFalse();
        assertThat(Permissions.allows(master, Permission.ALLOCATE_STALLS, null)).isFalse();
        assertThat(Permissions.allows(master, Permission.READ, null)).isTrue();
        assertThat(Permissions.allows(List.of(), Permission.READ, null)).as("no roles").isFalse();
    }

    @Test
    @DisplayName("one role's permission is not combined with another role's markets")
    void rolesDoNotLendEachOtherMarkets() {
        List<RoleGrant> both = List.of(new RoleGrant(Role.MARKET_MASTER, Set.of(marketA)),
                new RoleGrant(Role.ENFORCEMENT_OFFICER, Set.of(marketB)));

        assertThat(Permissions.allows(both, Permission.ALLOCATE_STALLS, marketB)).isFalse();
    }

    @Test
    @DisplayName("changing roles: not your own, not twice, not the clashing pair, not the operator, not another council's user")
    void changing() {
        UUID enforcer = user(new RoleGrant(Role.ENFORCEMENT_OFFICER, Set.of(marketA)));
        RoleGrant revenue = RoleGrant.councilWide(Role.REVENUE_OFFICER);

        assertThatThrownBy(() -> change.grant(council, enforcer, RoleGrant.councilWide(Role.AUDITOR), enforcer.toString()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> change.revoke(council, enforcer, Role.ENFORCEMENT_OFFICER, enforcer.toString()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> change.grant(council, enforcer, revenue, "x")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> change.grant(council, user(revenue), new RoleGrant(Role.ENFORCEMENT_OFFICER, Set.of(marketA)), "x"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> change.grant(council, enforcer, RoleGrant.councilWide(Role.MSIKA_OPERATOR), "x"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> change.grant(UUID.randomUUID(), enforcer, RoleGrant.councilWide(Role.AUDITOR), "x"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> change.revoke(council, enforcer, Role.AUDITOR, "x")).isInstanceOf(NotFoundException.class);

        change.grant(council, enforcer, RoleGrant.councilWide(Role.AUDITOR), "x");
        assertThatThrownBy(() -> change.grant(council, enforcer, RoleGrant.councilWide(Role.AUDITOR), "x"))
                .isInstanceOf(ConflictException.class);
        change.revoke(council, enforcer, Role.ENFORCEMENT_OFFICER, "x");
        change.grant(council, enforcer, revenue, "x");
        assertThat(users.byId.get(enforcer).holds(Role.REVENUE_OFFICER)).isTrue();
    }
}
