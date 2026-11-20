package zm.msika.zamsim.wallet;

/** The errors zamsim reports. The API turns them into 400, 404 and 409. */
public final class ZamsimExceptions {

    private ZamsimExceptions() {}

    /** The request is not valid. 400. */
    public static class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BadRequest(String message) {
            super(message);
        }
    }

    /** The wallet or transaction asked for does not exist. 404. */
    public static class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /** The idempotency key was already used for a different request. 409. */
    public static class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Conflict(String message) {
            super(message);
        }
    }
}
