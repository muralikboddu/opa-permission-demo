package com.example.permdemo.trades;

import com.example.permdemo.opa.UnsupportedRegoConstructException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedRegoConstructException.class)
    public ResponseEntity<Map<String, String>> unsupportedRego(UnsupportedRegoConstructException e) {
        // See that exception's Javadoc: this means the real OPA compile
        // response didn't match what PartialEvalTranslator expects.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage(),
                        "hint", "compare against GET /trades/_debug/compile?user=... and adjust PartialEvalTranslator"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> upstreamFailure(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
