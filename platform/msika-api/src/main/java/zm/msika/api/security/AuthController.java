package zm.msika.api.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.core.user.Authenticate;
import zm.msika.core.user.CouncilUser;

/** Sign in, and ask who you are signed in as. */
@RestController
class AuthController {

    private final Authenticate authenticate;
    private final TokenService tokens;

    AuthController(Authenticate authenticate, TokenService tokens) {
        this.authenticate = authenticate;
        this.tokens = tokens;
    }

    record LoginRequest(String email, String password) {

        /** Never prints the password, so it cannot reach a log by accident. */
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    record LoginResponse(String token, String tokenType, Instant expiresAt) {}

    record GrantBody(String role, java.util.List<UUID> marketIds) {}

    /** {@code permissions} is what the user may do somewhere; a market role may do it only in its markets. */
    record MeResponse(UUID userId, UUID councilId, java.util.List<GrantBody> roles, java.util.List<String> permissions, String email) {}

    /** Wrong password, unknown email and deactivated user all answer the same 401. */
    @PostMapping("/api/v1/auth/login")
    LoginResponse login(@RequestBody LoginRequest request) {
        CouncilUser user = authenticate.execute(request.email(), request.password());
        TokenService.IssuedToken token = tokens.issue(user);
        return new LoginResponse(token.value(), "Bearer", token.expiresAt());
    }

    @GetMapping("/api/v1/auth/me")
    MeResponse me() {
        MsikaPrincipal user = CurrentUser.require();
        return new MeResponse(user.userId(), user.councilId(), user.grants().stream()
                .map(g -> new GrantBody(g.role().name(), g.marketIds().stream().sorted().toList())).toList(),
                user.grants().stream().flatMap(g -> zm.msika.core.user.Permissions.of(g.role()).stream()).map(Enum::name)
                        .distinct().sorted().toList(), user.email());
    }
}
