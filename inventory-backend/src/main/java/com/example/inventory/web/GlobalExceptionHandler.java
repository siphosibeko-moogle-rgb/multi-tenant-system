package com.example.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.example.inventory.inventory.InsufficientStockException;

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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://api.example.com/problems/";

    /** @see SqlStates#INSUFFICIENT_PRIVILEGE */
    static final String INSUFFICIENT_PRIVILEGE = SqlStates.INSUFFICIENT_PRIVILEGE;

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
     * The contract's {@code InsufficientStock} response: 409 carrying
     * {@code productId}, {@code requested} and {@code available}.
     *
     * <p>Those three fields are the difference between an error a cashier can act
     * on and one they can only stare at. They are safe to disclose because the
     * caller is authenticated and already inside the tenant that owns the
     * product — RLS would not have let the write get this far otherwise.
     */
    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ProblemDetail> insufficientStock(InsufficientStockException e) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT,
                "Insufficient stock",
                "The requested quantity is not available", "insufficient-stock");

        ProblemDetail body = response.getBody();
        body.setProperty("productId", e.productId());
        body.setProperty("requested", e.requested());
        body.setProperty("available", e.available());
        return response;
    }

    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<ProblemDetail> preconditionFailed(PreconditionFailedException e) {
        // 412, not 409. The caller's request was well-formed and permitted; it
        // was simply based on a read that is no longer current. The fix is to
        // re-read and decide again, which is a different instruction from
        // "this conflicts with existing state".
        return problem(HttpStatus.PRECONDITION_FAILED, "Precondition failed", e.getMessage(),
                "stale-write");
    }

    /**
     * A malformed request parameter — 400, not the 500 a raw SQL enum cast
     * produces. See {@link BadRequestException}.
     */
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), "bad-request");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), "not-found");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException e) {
        // The caller authenticated but their role does not permit this. 403 is
        // correct here and does not conflict with T8: the endpoint's existence
        // is public in the contract, and no resource id is being confirmed.
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
                "Your role does not permit this action", "insufficient-role");
    }

    /**
     * A database privilege or row-level-security refusal.
     *
     * <p><strong>Keyed on SQLSTATE 42501, never on the exception type.</strong>
     * This is the important handler in this class and the reason it exists.
     *
     * <p>PostgreSQL raises {@code 42501} ({@code insufficient_privilege}) both
     * when a role lacks a table privilege and when a {@code WITH CHECK} clause
     * rejects a cross-tenant write. Spring's {@code SQLStateSQLExceptionTranslator}
     * maps that class of SQLSTATE to {@link org.springframework.jdbc.BadSqlGrammarException},
     * whose message names only the statement and never mentions row-level
     * security at all.
     *
     * <p>So an attempted tenant-isolation violation — the single most serious
     * event this system can observe — arrives looking like a typo in a query,
     * and anything dispatching on exception type would report and log it as a
     * malformed statement. That is why the dispatch below unwraps to the
     * {@link SQLException} and reads {@code getSQLState()}.
     *
     * <p>Deliberately still a generic 500 to the caller: a client has no
     * legitimate use for the distinction, and confirming "you were blocked by a
     * policy" tells a prober their guess was well-formed. The value is in the
     * server-side log line, which says what actually happened.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccess(DataAccessException e) {
        String sqlState = sqlStateOf(e);

        if (INSUFFICIENT_PRIVILEGE.equals(sqlState)) {
            log.error("SQLSTATE 42501 — a statement was refused by a privilege or an RLS policy. "
                    + "If this was a WITH CHECK failure it is an attempted cross-tenant write, "
                    + "not a malformed query. Cause: {}", rootMessage(e));

            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                    "The request could not be processed", "internal-error");
        }

        log.error("Unhandled data access failure (SQLSTATE {})", sqlState, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "The request could not be processed", "internal-error");
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
        // Spring MVC's own exceptions — wrong method, unsupported media type,
        // unreadable body, missing parameter — already carry the right status.
        // Without this branch they fall through to a 500, which is not cosmetic:
        // a client sending a GET to a POST-only route, or JSON the parser cannot
        // read, would be told the SERVER broke rather than that the request was
        // wrong, and would retry forever against something that can never work.
        //
        // Found by ContextPathTest, which expected 405 from a GET on
        // /auth/login and got 500 — three milestones after this catch-all was
        // written, because nothing had previously made a deliberately malformed
        // request.
        //
        // Checked with instanceof rather than a second @ExceptionHandler because
        // ErrorResponse is an interface, not a Throwable, so it cannot be named
        // as a handler type.
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return problem(status, status.getReasonPhrase(),
                    "The request could not be handled as sent", "bad-request");
        }

        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "The request could not be processed", "internal-error");
    }

    /**
     * Delegates to {@link SqlStates#of}. Kept here because this class is where
     * the reasoning lives and where readers look for it.
     */
    static String sqlStateOf(Throwable e) {
        return SqlStates.of(e);
    }

    private static String rootMessage(Throwable e) {
        return SqlStates.rootMessage(e);
    }

    /**
     * Builds the one response shape this class produces.
     *
     * <p>{@code type} is a stable slug rather than the message, so the Android
     * client can branch on the cause without parsing English that will be
     * reworded.
     */
    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail, String problemType) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(URI.create(PROBLEM_BASE + problemType));
        currentTraceId().ifPresent(id -> body.setProperty("traceId", id));
        return ResponseEntity.status(status).body(body);
    }

    private static Optional<String> currentTraceId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object id = attributes.getAttribute("traceId", RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable(id).map(Object::toString);
    }
}
