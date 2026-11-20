package zm.msika.api.security;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** A successfully authenticated request: a valid token whose user is still active. */
public class MsikaAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final MsikaPrincipal principal;
    private final transient Jwt token;

    MsikaAuthentication(MsikaPrincipal principal, Jwt token) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public MsikaPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }
}
