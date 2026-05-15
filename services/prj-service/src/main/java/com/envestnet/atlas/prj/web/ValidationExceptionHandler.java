package com.envestnet.atlas.prj.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates input-validation failures and malformed JSON bodies into
 * clean, predictable 400 responses.
 *
 * <p>Without this handler, Spring's default behaviour leaks
 * deserialization stack traces (the Jackson reason, the partial
 * object state, the line/column of the bad token) — useful in dev,
 * verbose + information-leaking in production.</p>
 *
 * <p>Response shape is the same across all three handlers so clients
 * can render errors consistently:
 * <pre>
 *   {
 *     "status": 400,
 *     "error":  "validation_failed",
 *     "message": "request body failed validation",
 *     "fieldErrors": [
 *       { "field": "name", "rejected": "ab", "message": "size must be between 3 and 200" },
 *       …
 *     ],
 *     "timestamp": "2026-05-11T12:34:56.789Z"
 *   }
 * </pre>
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    /**
     * Fires when a {@code @Valid @RequestBody} fails Bean Validation.
     * Aggregates all field-level violations into a single response.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("field",    fe.getField());
                    m.put("rejected", fe.getRejectedValue());
                    m.put("message",  fe.getDefaultMessage());
                    return m;
                })
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("validation_failed",
                        "request body failed validation",
                        fieldErrors));
    }

    /**
     * Fires when the request body can't be deserialized at all — bad
     * JSON, wrong content-type, completely empty body on a route that
     * expects one. We don't leak the parser's error text; just say
     * "malformed."
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("malformed_body",
                        "request body could not be parsed as JSON",
                        List.of()));
    }

    /**
     * Fires when a path/query param can't be converted to the
     * declared type (e.g. a non-UUID where a UUID is expected). The
     * default Spring behaviour returns 500 with a stack trace; clamp
     * it to 400 with a clean message instead.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("field",    ex.getName());
        err.put("rejected", ex.getValue());
        err.put("message",  "expected " + (ex.getRequiredType() == null
                ? "(unknown)" : ex.getRequiredType().getSimpleName()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope("type_mismatch",
                        "path or query parameter failed to bind",
                        List.of(err)));
    }

    private static Map<String, Object> envelope(String error, String message,
                                                  List<Map<String, Object>> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",      400);
        body.put("error",       error);
        body.put("message",     message);
        body.put("fieldErrors", fieldErrors);
        body.put("timestamp",   OffsetDateTime.now().toString());
        return body;
    }
}
