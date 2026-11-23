package zm.msika.core.levy;

/** The levy authority could not be reached or refused the request. The caller tries again later. */
public class LevyAuthorityUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LevyAuthorityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
