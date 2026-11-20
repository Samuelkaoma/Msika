package zm.msika.core.common;

/** Something the caller named does not exist (in their council). The API answers 404. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
