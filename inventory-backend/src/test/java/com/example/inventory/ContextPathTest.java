package com.example.inventory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application serves everything under {@code /api/v1}, and nothing at the
 * root.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code docs/openapi.yaml} has always declared
 * {@code servers: [{url: .../api/v1}]}, and the application served every
 * endpoint at the root instead. Nothing noticed for three milestones, because
 * nothing was checking: {@code ApiContractTest} validates the <em>document</em>
 * — that it parses, that it is valid OpenAPI 3.1, that its {@code $ref}s
 * resolve — and a document can be perfectly valid while the server ignores it
 * completely.
 *
 * <p>M3 found it the way this class of drift is always found: a client
 * generated from the contract pointed at {@code /api/v1} and would have 404ed on
 * every single call. That is a cheap discovery in a milestone whose whole point
 * is the client/server seam, and an expensive one anywhere else.
 *
 * <p>So this asserts on routes rather than on configuration. Reading back
 * {@code server.servlet.context-path} would only prove the property was set;
 * these make real requests and check where the application actually answers.
 */
@DisplayName("Base path")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextPathTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String rawPath) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + rawPath, e);
        }
    }

    @Test
    @DisplayName("an API endpoint is served under /api/v1")
    void endpointsLiveUnderTheVersionedPrefix() {
        // /me with no token: 401 proves the route EXISTS and the security chain
        // handled it. A 404 would mean nothing is mapped there at all, which is
        // the drift being guarded against.
        assertThat(get("/api/v1/me").statusCode())
                .as("/api/v1/me must be routed — 401 means it exists and rejected an "
                        + "unauthenticated caller, which is the correct answer here")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the same endpoint is NOT served at the root")
    void endpointsAreNotAlsoAtTheRoot() {
        // The half that actually catches the drift. If the prefix were dropped
        // again, this would go back to 401 and the test above would keep passing
        // only because /api/v1/... happened to be mapped by something else.
        assertThat(get("/me").statusCode())
                .as("nothing may answer at the root: the contract says /api/v1, and a server "
                        + "answering both is how a client ends up depending on the wrong one")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("actuator health moved under the prefix, and is reachable there")
    void actuatorIsReachableUnderThePrefix() {
        // Checked rather than assumed: management.endpoints.web.base-path is
        // relative to the context path, so /actuator became /api/v1/actuator the
        // moment the prefix was introduced. Anything probing the old location —
        // a Kubernetes liveness probe, a load balancer — would have started
        // failing silently.
        var response = get("/api/v1/actuator/health");

        assertThat(response.statusCode())
                .as("the health endpoint must still answer, and still without a token")
                .isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    @DisplayName("actuator is no longer at the root, which is the migration hazard")
    void actuatorIsNotAtTheRoot() {
        assertThat(get("/actuator/health").statusCode())
                .as("recorded deliberately: any probe still pointing at /actuator/health needs "
                        + "moving to /api/v1/actuator/health")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("an unauthenticated auth endpoint is reachable under the prefix")
    void authEndpointsAreReachable() {
        // GET on a POST-only path: 405 proves the route is mapped and the method
        // is what was wrong. 404 would mean the path itself is missing — and
        // /auth/login being unreachable is precisely what would have broken the
        // Android client.
        assertThat(get("/api/v1/auth/login").statusCode())
                .as("405 means mapped-but-wrong-method; 404 would mean the login route is gone")
                .isEqualTo(405);
    }
}
