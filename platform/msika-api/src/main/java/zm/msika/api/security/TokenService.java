package zm.msika.api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import zm.msika.core.user.CouncilUser;

/**
 * Issues and checks the short-lived tokens a signed-in user sends with each request.
 *
 * <p>A token only says "this is user X, signed by us, not expired". It carries no role: the role and
 * the active flag are read from the database on every request, so deactivating a user takes effect
 * at once even though their token is still valid.
 *
 * <p>The signing secret comes from {@code msika.auth.jwt-secret} (Base64, at least 32 bytes). If it is
 * not set, a random one is made at start-up and tokens stop working when the app restarts. That is
 * fine for development and wrong for a server, which must set it from its environment.
 */
@Component
public class TokenService {

    /** The issuer written into every token and required when checking one. */
    static final String ISSUER = "msika";

    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    /** What the API returns after sign-in. */
    public record IssuedToken(String value, Instant expiresAt) {}

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    TokenService(
            @Value("${msika.auth.jwt-secret:}") String base64Secret,
            @Value("${msika.auth.token-ttl:PT15M}") Duration ttl,
            Clock clock) {
        this.key = secretKey(base64Secret);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbus;
        this.ttl = ttl;
        this.clock = clock;
    }

    private static SecretKey secretKey(String base64Secret) {
        byte[] bytes;
        if (base64Secret == null || base64Secret.isBlank()) {
            bytes = new byte[32];
            new java.security.SecureRandom().nextBytes(bytes);
            LOG.warn("msika.auth.jwt-secret is not set: using a random secret, so tokens will not survive a restart");
        } else {
            bytes = Base64.getDecoder().decode(base64Secret.trim());
            if (bytes.length < 32) {
                throw new IllegalStateException("msika.auth.jwt-secret must be at least 32 bytes (Base64)");
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** A token for this user that expires after the configured time. */
    public IssuedToken issue(CouncilUser user) {
        return issue(user, ttl);
    }

    /**
     * A token that expires after the given time. A negative time gives an already-expired token, issued
     * in the past so that it still expires after it was issued (used by tests).
     */
    public IssuedToken issue(CouncilUser user, Duration lifetime) {
        Instant now = clock.instant();
        Instant expires = now.plus(lifetime);
        Instant issuedAt = lifetime.isNegative() ? expires.minusSeconds(60) : now;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .issuedAt(issuedAt)
                .expiresAt(expires)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(value, expires);
    }

    JwtDecoder decoder() {
        return decoder;
    }
}
