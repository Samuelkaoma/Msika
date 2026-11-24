package zm.msika.api.admin;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;

/**
 * Users of a council. Allowed for the council's own admin and for operators; an officer is read-only,
 * so the security rules refuse them (see SecurityConfiguration).
 */
@RestController
class UserAdminController {

    private final UserAdminService service;

    UserAdminController(UserAdminService service) {
        this.service = service;
    }

    record CreateUserRequest(String email, String password, String role, java.util.Set<UUID> marketIds) {

        /** Never prints the password. */
        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", role=" + role + "]";
        }
    }

    record SetActiveRequest(Boolean active) {}

    /** The password hash is never sent back. */
    record RoleBody(String role, java.util.Set<UUID> marketIds) {}

    record UserResponse(UUID id, UUID councilId, String email, java.util.List<RoleBody> roles, boolean active) {
        static UserResponse of(CouncilUser u) {
            return new UserResponse(u.id(), u.councilId(), u.email(), u.grants().stream()
                    .map(g -> new RoleBody(g.role().name(), g.marketIds())).toList(), u.active());
        }
    }

    @PostMapping("/api/v1/councils/{councilId}/users")
    ResponseEntity<UserResponse> create(@PathVariable UUID councilId, @RequestBody CreateUserRequest request) {
        CouncilUser user = service.create(councilId, request.email(), request.password(),
                grant(request.role(), request.marketIds()));
        return ResponseEntity.created(URI.create("/api/v1/councils/" + councilId + "/users/" + user.id()))
                .body(UserResponse.of(user));
    }

    /** PUT, because setting the flag to the same value twice has the same effect as once. */
    @PutMapping("/api/v1/councils/{councilId}/users/{userId}/active")
    UserResponse setActive(
            @PathVariable UUID councilId, @PathVariable UUID userId, @RequestBody SetActiveRequest request) {
        if (request.active() == null) {
            throw new InvalidInputException("active must be true or false");
        }
        return UserResponse.of(service.setActive(councilId, userId, request.active()));
    }

    private static Role role(String name) {
        try {
            return Role.valueOf(name == null ? "" : name);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("role must be one of COUNCIL_ADMIN, MARKET_MASTER, REVENUE_OFFICER, "
                    + "ENFORCEMENT_OFFICER, COUNCIL_OFFICER, AUDITOR");
        }
    }

    private static zm.msika.core.user.RoleGrant grant(String name, java.util.Set<UUID> marketIds) {
        return new zm.msika.core.user.RoleGrant(role(name), marketIds);
    }

    @org.springframework.web.bind.annotation.GetMapping("/api/v1/councils/{councilId}/users")
    java.util.List<UserResponse> list(@PathVariable UUID councilId) {
        return service.list(councilId).stream().map(UserResponse::of).toList();
    }

    @PostMapping("/api/v1/councils/{councilId}/users/{userId}/roles")
    UserResponse grant(@PathVariable UUID councilId, @PathVariable UUID userId, @RequestBody RoleBody request) {
        return UserResponse.of(service.grant(councilId, userId, grant(request.role(), request.marketIds())));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/councils/{councilId}/users/{userId}/roles/{role}")
    UserResponse revoke(@PathVariable UUID councilId, @PathVariable UUID userId, @PathVariable("role") String name) {
        return UserResponse.of(service.revoke(councilId, userId, role(name)));
    }
}
