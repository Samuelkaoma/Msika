package zm.msika.api.admin;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.CreateUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.SetUserActive;

/** Creates and deactivates council users, in a transaction, with an audit row for each change. */
@Service
@Transactional
class UserAdminService {

    private final CreateUser createUser;
    private final SetUserActive setUserActive;
    private final TenantContext tenant;
    private final AuditLog audit;

    UserAdminService(CreateUser createUser, SetUserActive setUserActive, TenantContext tenant, AuditLog audit) {
        this.createUser = createUser;
        this.setUserActive = setUserActive;
        this.tenant = tenant;
        this.audit = audit;
    }

    CouncilUser create(UUID councilId, String email, String password, Role role) {
        tenant.use(councilId);
        CouncilUser user = createUser.execute(councilId, email, password, role);
        audit.record(councilId, CurrentUser.actor(), "user.create", user.id() + " (" + user.role() + ")");
        return user;
    }

    CouncilUser setActive(UUID councilId, UUID userId, boolean active) {
        tenant.use(councilId);
        CouncilUser user = setUserActive.execute(councilId, userId, active);
        audit.record(councilId, CurrentUser.actor(), active ? "user.activate" : "user.deactivate", userId.toString());
        return user;
    }
}
