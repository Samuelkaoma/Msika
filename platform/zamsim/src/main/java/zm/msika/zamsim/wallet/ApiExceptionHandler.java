package zm.msika.zamsim.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zm.msika.zamsim.wallet.ZamsimExceptions.BadRequest;
import zm.msika.zamsim.wallet.ZamsimExceptions.Conflict;
import zm.msika.zamsim.wallet.ZamsimExceptions.NotFound;

/** One error shape for zamsim: {@code {"error": "...", "message": "..."}}. */
@RestControllerAdvice
class ApiExceptionHandler {

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
