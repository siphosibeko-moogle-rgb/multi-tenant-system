package com.example.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 403 handler, tested directly because nothing currently routes to it.
 *
 * <h2>Why this test exists in this shape</h2>
 *
 * <p>Every 403 this application produces today comes from {@code @PreAuthorize},
 * which raises inside the MVC dispatch where {@code GlobalExceptionHandler}
 * renders it — with a complete body. So unlike the 401, <strong>403 did not have
 * the missing-body gap</strong>, and {@code SecurityProblems.Denied} is wired
 * into a path that is presently unreachable: no rule in
 * {@code authorizeHttpRequests} denies an <em>authenticated</em> caller, and the
 * filter chain's {@code AccessDeniedHandler} only fires for one that does.
 *
 * <p>That left a choice between deleting the handler as dead code and keeping it
 * as a guard. It is kept, because the gap reappears the moment anyone adds a
 * {@code .hasRole(...)} rule to the chain — a one-line change that would
 * silently start emitting bodiless 403s, which is exactly how the 401 gap
 * survived from M1 to M3 unnoticed.
 *
 * <p>But an unreachable handler that is also untested is decoration, and would
 * be just as broken as no handler on the day it is first needed. So it is
 * exercised directly here. This is deliberately NOT an HTTP test: there is no
 * request that reaches it, and inventing a security rule purely to make one
 * would be testing the fixture rather than the code.
 */
@DisplayName("SecurityProblems.Denied")
class SecurityProblemsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("writes a complete RFC 9457 body with the request path as instance")
    void deniedWritesAProblem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");
        request.setRequestURI("/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityProblems.Denied()
                .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");

        JsonNode body = JSON.readTree(response.getContentAsString());

        // Presence before value, per CLAUDE.md §5: an absent field read through
        // asString() is indistinguishable from an empty one.
        assertThat(body.has("type")).isTrue();
        assertThat(body.has("title")).isTrue();
        assertThat(body.has("status")).isTrue();
        assertThat(body.has("detail")).isTrue();
        assertThat(body.has("instance")).isTrue();

        assertThat(body.get("type").asString())
                .isEqualTo("https://api.example.com/problems/insufficient-role");
        assertThat(body.get("title").asString()).isEqualTo("Forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/users");

        // Same wording as GlobalExceptionHandler.accessDenied. A client must not
        // be able to tell which layer refused it.
        assertThat(body.get("detail").asString())
                .isEqualTo("Your role does not permit this action");
    }

    @Test
    @DisplayName("writes nothing to an already-committed response")
    void deniedLeavesACommittedResponseAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.setRequestURI("/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Something earlier in the chain already began the response. Writing a
        // second body here would corrupt the first rather than replace it.
        response.getWriter().write("already sent");
        response.flushBuffer();

        new SecurityProblems.Denied()
                .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }
}
