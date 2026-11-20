package zm.msika.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is making the current request. */
public final class CurrentUser {

    private CurrentUser() {}

    /** @throws IllegalStateException if nobody is signed in (a bug: the security rules should have stopped it) */
    public static MsikaPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof MsikaAuthentication msika) {
            return msika.getPrincipal();
        }
        throw new IllegalStateException("no signed-in user on this request");
    }

    /** The current user's id as text, for the audit log. */
    public static String actor() {
        return require().userId().toString();
    }
}
