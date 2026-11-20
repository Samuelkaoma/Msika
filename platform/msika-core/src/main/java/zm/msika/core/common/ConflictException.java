package zm.msika.core.common;

/** The request is valid but clashes with the current state. The API answers 409. */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
