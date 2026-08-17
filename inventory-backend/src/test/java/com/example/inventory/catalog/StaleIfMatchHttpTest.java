package com.example.inventory.catalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stale {@code If-Match} criterion, asserted as an HTTP status.
 *
 * <p>{@code ConcurrentStaleWriteTest} proves the important behaviour — two
 * concurrent writers, one refused — but it does so at the service layer, where
 * the answer is a {@code PreconditionFailedException}. The milestone criterion
 * is "a stale {@code If-Match} returns 412", which is a claim about the wire.
 * Nothing was checking that the exception became a 412 rather than a 409 or a
 * 500, or that the {@code ETag} header was emitted at all for a client to send
 * back.
 *
 * <p>Small test, but the alternative is a criterion that everyone believes is
 * covered because a differently-shaped test with a similar name is green.
 */
@DisplayName("Stale If-Match over HTTP")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaleIfMatchHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'ifm-%s', 'Shop')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'own-%s@example.test', 'Olive', 'owner', 'active')
                    """.formatted(userId, tenantId, tenantId.toString().substring(0, 8)));
        }
        seeded = true;
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    /** PATCH with an If-Match header — beyond what HttpTestClient offers. */
    private HttpResponse<String> patch(String path, String body, String ifMatch) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + HttpTestClient.BASE_PATH + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token())
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
            if (ifMatch != null) {
                request.header("If-Match", ifMatch);
            }
            return HttpClient.newHttpClient()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("request failed", e);
        }
    }

    private static String product(String sku, String name) {
        return """
                {"sku":"%s","name":"%s","costPrice":1.00,"sellingPrice":2.00,"taxRate":0.15}
                """.formatted(sku, name);
    }

    @Test
    @DisplayName("a stale If-Match returns 412; the current one returns 200")
    void staleIfMatchIsRejectedOverHttp() {
        String sku = "SKU-IFM-" + UUID.randomUUID().toString().substring(0, 6);

        var created = http().postWithToken("/products", product(sku, "Original"), token());
        assertThat(created.status()).isEqualTo(201);

        String etag = created.headers().first("ETag");
        assertThat(etag)
                .as("the create response must carry an ETag — without one the client has nothing "
                        + "to send back, and If-Match could never be exercised at all")
                .isNotNull();

        String productId = created.at("/id");

        // First write with the current version: accepted, and the ETag moves on.
        var accepted = patch("/products/" + productId, product(sku, "Renamed"), etag);
        assertThat(accepted.statusCode())
                .as("a current If-Match must be accepted — otherwise 412 below would prove "
                        + "only that the endpoint refuses everything")
                .isEqualTo(200);

        String newETag = accepted.headers().firstValue("ETag").orElse(null);
        assertThat(newETag)
                .as("the response must carry the NEW version, or a client cannot make a second "
                        + "conditional write without re-reading")
                .isNotNull()
                .isNotEqualTo(etag);

        // Second write with the version we already spent.
        var refused = patch("/products/" + productId, product(sku, "Too Late"), etag);

        assertThat(refused.statusCode())
                .as("THE criterion: a stale If-Match returns 412 — not 409, not 500")
                .isEqualTo(412);

        assertThat(refused.headers().firstValue("Content-Type").orElse(""))
                .as("and it is a problem detail like every other error")
                .contains("application/problem+json");

        var body = http().get("/products/" + productId, token());
        assertThat(body.at("/name"))
                .as("the refused write must not have been applied")
                .isEqualTo("Renamed");
    }
}
