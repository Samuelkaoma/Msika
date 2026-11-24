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

    /**
     * A phone number as the current user may see it: in full for people who may change things, masked for read-only
     * users (they have no need to call anyone). Every list that shows phones goes through here.
     */
    public static String showPhone(zm.msika.core.phone.PhoneNumber phone) {
        return seesFullPhones() ? phone.e164() : phone.masked();
    }

    /** True if the current user may see phone numbers in full. */
    public static boolean seesFullPhones() {
        return zm.msika.core.user.Permissions.allows(require().grants(), zm.msika.core.user.Permission.SEE_PHONES, null);
    }

    /** The current user's id as text, for the audit log. */
    public static String actor() {
        return require().userId().toString();
    }
}
