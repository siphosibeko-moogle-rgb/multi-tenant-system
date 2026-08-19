package com.example.inventory.web;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * RFC 9457 bodies for the two rejections Spring Security writes itself.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link GlobalExceptionHandler} renders every error raised <em>inside</em>
 * the MVC dispatch. A 401 is not one of those: it is written by the security
 * filter chain before any controller is reached, and the default entry point
 * emits a status line and a {@code WWW-Authenticate} header with <strong>no
 * body at all</strong>. The contract's {@code Unauthorized} response declares a
 * {@code Problem}, so the two disagreed, and
 * {@code ResponseRequiredFieldsHttpTest} proved it over HTTP rather than by
 * reading either side.
 *
 * <p>That disagreement matters more than its size suggests. A 401 is the
 * most-travelled error path the mobile client has — every expired access token
 * lands on it — and a client generated from the contract types the response as
 * a {@code Problem}. It survived only because {@code TokenAuthenticator} keys on
 * the status code and never reads the body, which is luck rather than design:
 * the cost appears the moment a refresh itself fails and the user is shown a
 * generic message where the server had something specific to say.
 *
 * <h2>The detail is deliberately uninformative</h2>
 *
 * <p>One message for every 401, whether the token was absent, expired,
 * malformed, signed with the wrong key, or a refresh token presented where an
 * access token belongs. <strong>Distinguishing them would be an oracle.</strong>
 * "Expired" tells a caller holding a stolen token that it was otherwise valid
 * and merely needs refreshing; "malformed" versus "bad signature" tells someone
 * forging tokens which half of their attempt was right. Neither helps a
 * legitimate client, which already knows what it sent and whose only correct
 * response — re-authenticate — is the same in every case.
 *
 * <p>This is the same reasoning as T8 (another tenant's resource is 404, not
 * 403) and as {@code /auth/logout} always returning 204: where an error code
 * could confirm a guess, it does not.
 *
 * <p>The {@code type} slug still separates this from the login failure that
 * {@code GlobalExceptionHandler} renders as {@code invalid-credentials}. That
 * distinction is safe because it is about <em>which endpoint</em> refused, not
 * about what was wrong with the credential: a client can branch "refresh and
 * retry" against "ask the user to sign in again" without learning anything it
 * did not already know.
 *
 * <h2>Wrapping, not replacing</h2>
 *
 * <p>{@link EntryPoint} delegates to Spring's
 * {@code BearerTokenAuthenticationEntryPoint} first so the
 * {@code WWW-Authenticate} header it computes — including the OAuth 2.0 error
 * code where there is one — is still sent. Only the body is added. Replacing the
 * entry point outright would silently drop that header, trading one contract
 * violation for another.
 */
public final class SecurityProblems {

    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private SecurityProblems() {
    }

    /**
     * Writes one problem body.
     *
     * <p>Built as an explicit map rather than a {@link org.springframework.http.ProblemDetail}
     * because that type is serialized through a Spring-registered Jackson mixin
     * that is set up for the MVC message converters, not for a raw servlet write
     * from inside the filter chain. Constructing the JSON here keeps the field
     * names and their order a property of this class instead of a property of
     * whichever ObjectMapper configuration happens to be reachable.
     */
    private static void write(HttpServletResponse response, HttpServletRequest request,
                              HttpStatus status, String title, String detail, String problemType)
            throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create(PROBLEM_BASE + problemType).toString());
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        // getRequestURI includes the context path, so this reads "/api/v1/me" —
        // matching what the MVC handlers emit, which take it from the same place.
        body.put("instance", request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(body));
    }

    /** 401 for an absent, expired or otherwise unusable access token. */
    public static final class EntryPoint implements AuthenticationEntryPoint {

        private final AuthenticationEntryPoint delegate;

        public EntryPoint(AuthenticationEntryPoint delegate) {
            this.delegate = delegate;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException)
                throws IOException, jakarta.servlet.ServletException {
            // Delegate first: this is what sets WWW-Authenticate. It also sets
            // the status, which write() then sets again to the same value —
            // harmless, and cheaper than depending on the delegate's ordering.
            delegate.commence(request, response, authException);

            if (response.isCommitted()) {
                return;
            }
            write(response, request, HttpStatus.UNAUTHORIZED,
                    "Authentication required",
                    // Deliberately identical for every cause. See the class note.
                    "This endpoint requires a valid access token.",
                    "unauthenticated");
        }
    }

    /** 403 for a caller who authenticated but whose role does not permit this. */
    public static final class Denied implements AccessDeniedHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException deniedException) throws IOException {
            if (response.isCommitted()) {
                return;
            }
            // Wording matches GlobalExceptionHandler.accessDenied, which covers
            // the same refusal when it is raised by @PreAuthorize inside the
            // dispatch rather than by the filter chain. A client should not be
            // able to tell which layer said no.
            write(response, request, HttpStatus.FORBIDDEN,
                    "Forbidden",
                    "Your role does not permit this action",
                    "insufficient-role");
        }
    }
}
