package com.example.inventory.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A minimal HTTP client for the auth tests.
 *
 * <p>Real requests against a real server on a random port, rather than MockMvc.
 * Two reasons, one practical and one substantive.
 *
 * <p>Practical: Boot 4.1's {@code spring-boot-test-autoconfigure} no longer
 * contains MockMvc support — {@code @AutoConfigureMockMvc} lives in a separate
 * module that is not on this project's classpath, and adding a dependency to get
 * it would need asking.
 *
 * <p>Substantive: these tests are largely about the security filter chain —
 * which paths are open, what an unauthenticated request gets, whether a token is
 * rejected. MockMvc approximates that chain; a real request exercises it,
 * including the status codes and headers Spring Security writes directly to the
 * response rather than through a controller. For this particular subject the
 * real thing is worth the extra second.
 *
 * <p>Uses the JDK's own {@link HttpClient}, so it adds no dependency at all.
 */
public final class HttpTestClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    /**
     * The versioned base path is applied here, once, so every test can keep
     * writing logical paths like "/auth/login".
     *
     * <p>It must match {@code server.servlet.context-path}, which matches the
     * {@code servers} URL in docs/openapi.yaml. Deliberately NOT read from the
     * application's own configuration: a test that asks the app where it serves
     * and then checks it serves there proves nothing. {@code ContextPathTest}
     * makes raw requests against literal paths for the same reason.
     */
    public static final String BASE_PATH = "/api/v1";

    public HttpTestClient(int port) {
        this.baseUrl = "http://localhost:" + port + BASE_PATH;
    }

    public record Response(int status, String body, HttpHeadersView headers) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String at(String pointer) {
            return json().at(pointer).asString();
        }
    }

    /** Just the header lookups these tests need. */
    public record HttpHeadersView(HttpResponse<String> raw) {
        public String first(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    public Response post(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response postWithToken(String path, String jsonBody, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    /**
     * POST with arbitrary extra headers — {@code Idempotency-Key}, so far.
     *
     * <p>Separate from {@link #postWithToken} rather than replacing it: every
     * existing caller passes no extra headers, and widening the common helper's
     * signature would mean touching a hundred call sites to express nothing.
     */
    public Response postWithHeaders(String path, String jsonBody, String bearerToken,
                                    java.util.Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        headers.forEach(request::header);
        return send(request);
    }

    public Response get(String path, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    new HttpHeadersView(response));
        } catch (Exception e) {
            throw new IllegalStateException("request failed", e);
        }
    }

    public static String json(Object... keyValuePairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(keyValuePairs[i]).append("\":");
            Object value = keyValuePairs[i + 1];
            if (value == null) {
                out.append("null");
            } else {
                out.append('"').append(value).append('"');
            }
        }
        return out.append('}').toString();
    }
}
