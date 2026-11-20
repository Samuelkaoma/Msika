package zm.msika.api.security;

import jakarta.servlet.DispatcherType;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.UserRepository;

/**
 * Who may call what. Every endpoint is DENIED unless a rule below allows it (ARCHITECTURE.md section 9).
 *
 * <p>A new endpoint that nobody wrote a rule for answers 403, not 200. Adding a controller method
 * therefore does nothing until its rule is added here, on purpose.
 *
 * <p>The API is stateless: no sessions and no cookies, so no CSRF risk. Every request carries a bearer
 * token, which is checked and then matched to its user row (see {@link #userRecheck}).
 */
@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, TokenService tokens, UserRepository users) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .decoder(tokens.decoder())
                        .jwtAuthenticationConverter(userRecheck(users))))
                .authorizeHttpRequests(a -> a
                        // The framework's own error page is reachable after a controller error.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/health", "/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        // Only Msika staff create councils.
                        .requestMatchers(HttpMethod.POST, "/api/v1/councils").hasRole(Role.MSIKA_OPERATOR.name())
                        // Everything inside a council: see councilScope.
                        .requestMatchers("/api/v1/councils/{councilId}/**").access(councilScope())
                        // Anything not listed above is denied.
                        .anyRequest().denyAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(Customizer.withDefaults());
        return http.build();
    }

    /**
     * After the token's signature and expiry are checked, load the user's row from the database. If
     * the user is gone or deactivated the request is refused with 401, even though the token is still
     * valid. The role comes from the row, never from the token.
     */
    static Converter<org.springframework.security.oauth2.jwt.Jwt, AbstractAuthenticationToken> userRecheck(
            UserRepository users) {
        return jwt -> {
            UUID userId;
            try {
                userId = UUID.fromString(jwt.getSubject());
            } catch (RuntimeException e) {
                throw new BadCredentialsException("the token does not name a user");
            }
            CouncilUser user = users.findById(userId)
                    .filter(CouncilUser::active)
                    .orElseThrow(() -> new BadCredentialsException("the user is not active"));
            return new MsikaAuthentication(MsikaPrincipal.of(user), jwt);
        };
    }

    /**
     * The rule for {@code /api/v1/councils/{councilId}/...}. Allowed when:
     * an operator (any council, any method); or a user of THAT council, who may read (GET, HEAD,
     * OPTIONS) whatever their role and change things only if they are not read-only.
     */
    static AuthorizationManager<RequestAuthorizationContext> councilScope() {
        return (authentication, context) -> {
            if (!(authentication.get() instanceof MsikaAuthentication signedIn)) {
                return new AuthorizationDecision(false);
            }
            MsikaPrincipal user = signedIn.getPrincipal();
            if (user.role() == Role.MSIKA_OPERATOR) {
                return new AuthorizationDecision(true);
            }
            String method = context.getRequest().getMethod();
            boolean reading = HttpMethod.GET.matches(method)
                    || HttpMethod.HEAD.matches(method)
                    || HttpMethod.OPTIONS.matches(method);
            boolean ownCouncil = user.councilId() != null
                    && user.councilId().toString().equals(context.getVariables().get("councilId"));
            return new AuthorizationDecision(ownCouncil && (reading || !user.role().isReadOnly()));
        };
    }
}
