package zm.msika.core.common;

import java.util.List;

/**
 * The input is not acceptable. The API answers 400. For an uploaded file, {@link #rows()} lists every
 * bad row (up to a limit) so the person can fix them all in one go.
 */
public class InvalidInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<RowError> rows;

    public InvalidInputException(String message) {
        this(message, List.of());
    }

    public InvalidInputException(String message, List<RowError> rows) {
        super(message);
        this.rows = List.copyOf(rows);
    }

    /** The bad rows of an uploaded file; empty when the problem is not about a particular row. */
    public List<RowError> rows() {
        return rows;
    }
}
