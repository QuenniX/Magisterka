package pl.magisterka.backend.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    /** 4xx with JSON { message, code } so UI never sees raw Spring error. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "Error";
        String code = ex.getStatusCode().value() == 409 ? ErrorResponse.CONFLICT : ErrorResponse.BAD_REQUEST;
        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(message, code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message, ErrorResponse.VALIDATION_ERROR));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Invalid request body (e.g. JSON parse error)";
        if (message.length() > 200) message = message.substring(0, 200) + "...";
        return ResponseEntity.badRequest().body(new ErrorResponse(message, ErrorResponse.VALIDATION_ERROR));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Constraint violation";
        if (message.length() > 300) message = message.substring(0, 300) + "...";
        return ResponseEntity.badRequest().body(new ErrorResponse("Błąd zapisu (constraint): " + message, ErrorResponse.VALIDATION_ERROR));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        if ("Schedule already exists".equals(ex.getMessage())) {
            return ResponseEntity.status(409).body(new ErrorResponse(ex.getMessage(), ErrorResponse.CONFLICT));
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage(), ErrorResponse.BAD_REQUEST));
    }

    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(org.springframework.security.authentication.BadCredentialsException ex) {
        return ResponseEntity.status(401).body(new ErrorResponse(ex.getMessage(), "UNAUTHORIZED"));
    }
}
