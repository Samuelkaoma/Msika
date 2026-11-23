package zm.msika.elevysim.levy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The errors elevysim reports, and the one error shape it answers with: {@code {"error": "...", "message": "..."}}. */
public final class LevyExceptions {

    private LevyExceptions() {}

    /** 400. */
    public static class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BadRequest(String message) {
            super(message);
        }
    }

    /** 404. */
    public static class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /** 409. */
    public static class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Conflict(String message) {
            super(message);
        }
    }

    @RestControllerAdvice
    static class Handler {
        record ErrorResponse(String error, String message) {}

        @ExceptionHandler(BadRequest.class)
        ResponseEntity<ErrorResponse> badRequest(BadRequest e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("bad_request", e.getMessage()));
        }

        @ExceptionHandler(NotFound.class)
        ResponseEntity<ErrorResponse> notFound(NotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", e.getMessage()));
        }

        @ExceptionHandler(Conflict.class)
        ResponseEntity<ErrorResponse> conflict(Conflict e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("conflict", e.getMessage()));
        }
    }
}
