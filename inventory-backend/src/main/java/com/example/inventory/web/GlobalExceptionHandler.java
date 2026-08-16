package com.example.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Turns exceptions into RFC 9457 problem details, one shape everywhere
 * (CLAUDE.md §4).
 *
 * <p>Every response here is {@code application/problem+json}. No bare strings,
 * no stack traces, no Spring default error body — the Android client parses one
 * structure and only one.
 *
 * <h2>Nothing here leaks why</h2>
 *
 * <p>The 401 handler passes the exception's message through, and every message
 * the auth package supplies is deliberately uninformative for that reason. The
 * 500 handler does the opposite: it never passes the message through, because an
 * unexpected exception's message is written for a developer and routinely
 * contains SQL, table names or a connection string.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String PROBLEM_BASE = "https://api.example.com/problems/";

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ProblemDetail> unauthorized(UnauthorizedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", e.getMessage(),
                "invalid-credentials");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), e.problemType());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        ResponseEntity<ProblemDetail> response = problem(
                HttpStatus.TOO_MANY_REQUESTS, "Too many requests", e.getMessage(), "rate-limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfter().toSeconds()))
                .body(response.getBody());
    }

    /**
     * Bean-validation failures, as the {@code errors} array the contract defines.
     *
     * <p>Field-level detail is safe here in a way it is not for authentication:
     * these are the caller's own inputs being echoed back, and telling them
     * "slug must be lowercase" reveals nothing they did not send.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of(
                        "field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage()))
                .toList();

        ResponseEntity<ProblemDetail> response = problem(
                HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
                "One or more fields are invalid", "validation-failed");
        response.getBody().setProperty("errors", errors);
        return response;
    }

    /**
     * The catch-all.
     *
     * <p>Deliberately last and deliberately vague. Anything reaching here is a
     * bug, and the response says so without saying what: the detail is a fixed
     * string, and the {@code traceId} is how the actual cause is found in the
     * logs.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "The request could not be processed", "internal-error");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail, String problemType) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(URI.create(PROBLEM_BASE + problemType));
        currentTraceId().ifPresent(id -> body.setProperty("traceId", id));
        return ResponseEntity.status(status).body(body);
    }

    private static java.util.Optional<String> currentTraceId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return java.util.Optional.empty();
        }
        Object id = attributes.getAttribute("traceId", RequestAttributes.SCOPE_REQUEST);
        return java.util.Optional.ofNullable(id).map(Object::toString);
    }
}
