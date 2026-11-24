package zm.msika.api.admin;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.user.ChangeRoles;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.CreateUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.RoleGrant;
import zm.msika.core.user.SetUserActive;
import zm.msika.core.user.UserRepository;

/** Creates and deactivates council users, in a transaction, with an audit row for each change. */
@Service
@Transactional
class UserAdminService {

    private final CreateUser createUser;
    private final SetUserActive setUserActive;
    private final TenantContext tenant;
    private final AuditLog audit;
    private final ChangeRoles changeRoles;
    private final UserRepository users;

    UserAdminService(CreateUser createUser, SetUserActive setUserActive, TenantContext tenant, AuditLog audit,
            ChangeRoles changeRoles, UserRepository users) {
        this.changeRoles = changeRoles;
        this.users = users;
        this.createUser = createUser;
        this.setUserActive = setUserActive;
        this.tenant = tenant;
        this.audit = audit;
    }

    CouncilUser create(UUID councilId, String email, String password, RoleGrant grant) {
        tenant.use(councilId);
        CouncilUser user = createUser.execute(councilId, email, password, grant, CurrentUser.actor());
        audit.record(councilId, CurrentUser.actor(), "user.create", user.id() + " (" + grant.role() + ")");
        return user;
    }

    java.util.List<CouncilUser> list(UUID councilId) {
        tenant.use(councilId);
        return users.list(councilId);
    }

    CouncilUser grant(UUID councilId, UUID userId, RoleGrant grant) {
        tenant.use(councilId);
        changeRoles.grant(councilId, userId, grant, CurrentUser.actor());
        audit.record(councilId, CurrentUser.actor(), "user.role.grant", userId + " " + grant.role() + " " + grant.marketIds());
        return users.findInCouncil(councilId, userId).orElseThrow();
    }

    CouncilUser revoke(UUID councilId, UUID userId, Role role) {
        tenant.use(councilId);
        changeRoles.revoke(councilId, userId, role, CurrentUser.actor());
        audit.record(councilId, CurrentUser.actor(), "user.role.revoke", userId + " " + role);
        return users.findInCouncil(councilId, userId).orElseThrow();
    }

    CouncilUser setActive(UUID councilId, UUID userId, boolean active) {
        tenant.use(councilId);
        CouncilUser user = setUserActive.execute(councilId, userId, active);
        audit.record(councilId, CurrentUser.actor(), active ? "user.activate" : "user.deactivate", userId.toString());
        return user;
    }
}
