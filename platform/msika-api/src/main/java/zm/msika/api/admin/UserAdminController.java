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

    record CreateUserRequest(String email, String password, String role) {

        /** Never prints the password. */
        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", role=" + role + "]";
        }
    }

    record SetActiveRequest(Boolean active) {}

    /** The password hash is never sent back. */
    record UserResponse(UUID id, UUID councilId, String email, String role, boolean active) {
        static UserResponse of(CouncilUser u) {
            return new UserResponse(u.id(), u.councilId(), u.email(), u.role().name(), u.active());
        }
    }

    @PostMapping("/api/v1/councils/{councilId}/users")
    ResponseEntity<UserResponse> create(@PathVariable UUID councilId, @RequestBody CreateUserRequest request) {
        Role role;
        try {
            role = request.role() == null ? null : Role.valueOf(request.role());
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("role must be COUNCIL_ADMIN or COUNCIL_OFFICER");
        }
        CouncilUser user = service.create(councilId, request.email(), request.password(), role);
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
}
