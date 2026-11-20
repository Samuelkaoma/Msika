package zm.msika.api.admin;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.common.RowError;
import zm.msika.core.payments.PaymentsUnavailableException;
import zm.msika.core.user.AuthenticationFailedException;

/**
 * Turns the plain exceptions of msika-core into HTTP answers with one consistent body:
 * {@code {"error": "...", "message": "...", "rows": [{"row": 3, "message": "..."}]}}.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    record ErrorResponse(String error, String message, List<RowError> rows) {}

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ErrorResponse> invalidInput(InvalidInputException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_input", e.getMessage(), e.rows());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ErrorResponse> authenticationFailed(AuthenticationFailedException e) {
        return respond(HttpStatus.UNAUTHORIZED, "unauthenticated", e.getMessage(), List.of());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "not_found", e.getMessage(), List.of());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return respond(HttpStatus.CONFLICT, "conflict", e.getMessage(), List.of());
    }

    /** The payments system could not be asked. Whatever was saved stays saved; sending the same command again retries. */
    @ExceptionHandler(PaymentsUnavailableException.class)
    ResponseEntity<ErrorResponse> paymentsUnavailable(PaymentsUnavailableException e) {
        return respond(HttpStatus.BAD_GATEWAY, "payments_unavailable",
                e.getMessage() + ". The request is saved: send the same command again (same Idempotency-Key) to retry.",
                List.of());
    }

    private static ResponseEntity<ErrorResponse> respond(
            HttpStatus status, String error, String message, List<RowError> rows) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message, rows));
    }
}
