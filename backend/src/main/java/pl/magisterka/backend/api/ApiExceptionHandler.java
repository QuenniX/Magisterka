package pl.magisterka.backend.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        if ("Schedule already exists".equals(ex.getMessage())) {
            return ResponseEntity.status(409).body(ex.getMessage());
        }
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
