package com.example.inventory.users;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.AuthProperties;
import com.example.inventory.auth.JwtService;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import static com.example.inventory.auth.HttpTestClient.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Role enforcement on {@code /users}, asserted in both directions.
 *
 * <p>Every role gets an <strong>allow</strong> case and a <strong>deny</strong>
 * case. A permission test that only exercises the happy path proves nothing: a
 * gate that accidentally permits everyone passes it perfectly, and the absence
 * of a viewer from the happy-path test is not evidence that a viewer is refused.
 * So a viewer is asked to write, and must be told 403.
 *
 * <h2>The gates under test, and a contradiction in the contract</h2>
 *
 * <p>{@code docs/openapi.yaml} says two incompatible things: the header above
 * {@code /users} reads "owner/manager only", while the {@code UserRole}
 * description gives "users" to {@code owner} and lists {@code manager} as
 * "catalog, purchasing, reports, adjustments" — without users. See
 * {@link UsersController} for the split chosen and why: read is owner+manager,
 * every mutation is owner-only, because invite and PATCH both take a role and
 * are therefore privilege-escalation paths.
 *
 * <p>If that call is wrong, this test is where it is expressed, and widening it
 * is a one-line change per case.
 */
@DisplayName("Role enforcement on /users")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoleEnforcementTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AuthProperties authProperties;

    private static UUID tenantId;
    private static UUID ownerId;
    private static boolean seeded;

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    /**
     * One tenant with a user in each role.
     *
     * <p>Seeded over the owner connection, which is the container superuser and
     * so bypasses RLS — right for a fixture, and never used for an assertion.
     */
    @BeforeEach
    void seedOneTenantPerRole() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        ownerId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'roles-%s', 'Roles Ltd')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));

            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Olive Owner', 'owner', 'active')
                    """.formatted(ownerId, tenantId, tenantId.toString().substring(0, 8)));

            for (String role : List.of("manager", "clerk", "viewer")) {
                stmt.execute("""
                        INSERT INTO users (tenant_id, email, full_name, role, status)
                        VALUES ('%s', '%s-%s@example.test', '%s Person', '%s', 'active')
                        """.formatted(tenantId, role, tenantId.toString().substring(0, 8),
                        role, role));
            }
        }
        seeded = true;
    }

    /**
     * A real, signed access token carrying the given role.
     *
     * <p>Minted through {@link JwtService} rather than hand-built, so these tests
     * exercise the same claim shape and the same signing path production uses —
     * including the {@code role} claim that {@code SecurityConfig}'s converter
     * turns into an authority.
     */
    private String tokenFor(String role) {
        return jwtService.issueAccessToken(tenantId, ownerId, role).value();
    }

    // ------------------------------------------------------------------
    // Reads: owner and manager yes, clerk and viewer no
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("GET /users")
    class ListUsers {

        @Test
        @DisplayName("owner and manager are allowed")
        void ownerAndManagerMayList() {
            SoftAssertions.assertSoftly(softly -> {
                for (String role : List.of("owner", "manager")) {
                    var response = http().get("/users", tokenFor(role));
                    softly.assertThat(response.status())
                            .as("%s must be able to list the team", role)
                            .isEqualTo(200);
                    softly.assertThat(response.json().at("/items").size())
                            .as("%s should see the four seeded users", role)
                            .isGreaterThanOrEqualTo(4);
                }
            });
        }

        @Test
        @DisplayName("clerk and viewer are refused with 403")
        void clerkAndViewerMayNotList() {
            SoftAssertions.assertSoftly(softly -> {
                for (String role : List.of("clerk", "viewer")) {
                    softly.assertThat(http().get("/users", tokenFor(role)).status())
                            .as("%s must not be able to enumerate the team", role)
                            .isEqualTo(403);
                }
            });
        }

        @Test
        @DisplayName("a token with no role claim is refused, not defaulted")
        void aRolelessTokenIsRefused() {
            // The failure mode this guards: if the authorities converter silently
            // yielded an empty list AND the gate were relaxed, a claimless token
            // would sail through. It must be denied instead.
            //
            // Built through the encoder rather than JwtService, because
            // JwtClaimsSet rejects a null claim value — there is no way to ask
            // the service for a token it would never legitimately issue.
            Instant now = Instant.now();
            String roleless = jwtEncoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(AuthProperties.SIGNING_ALGORITHM).build(),
                    JwtClaimsSet.builder()
                            .issuer(authProperties.issuer())
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(300))
                            .subject(ownerId.toString())
                            .id(UUID.randomUUID().toString())
                            .claim("tid", tenantId.toString())
                            .build())).getTokenValue();

            assertThat(http().get("/users", roleless).status())
                    .as("no role claim means no authority means no access")
                    .isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------
    // Writes: owner only
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Mutations")
    class Mutations {

        private String invitePayload() {
            return json("email", "invited-" + UUID.randomUUID() + "@example.test",
                    "fullName", "Ingrid Invitee",
                    "role", "clerk");
        }

        @Test
        @DisplayName("every non-owner role is refused a write")
        void nonOwnersMayNotWrite() {
            // The case that matters most: a viewer must be REFUSED a write, not
            // merely absent from the happy path. Manager is in here too — it can
            // read, which makes it the role most likely to be widened by accident.
            SoftAssertions.assertSoftly(softly -> {
                for (String role : List.of("manager", "clerk", "viewer")) {
                    String token = tokenFor(role);

                    softly.assertThat(postAs("/users", invitePayload(), token))
                            .as("%s must not be able to invite — invite takes a role, so it is a "
                                    + "privilege-escalation path", role)
                            .isEqualTo(403);

                    softly.assertThat(patchAs("/users/" + ownerId,
                                    json("role", "viewer"), token))
                            .as("%s must not be able to change a role — otherwise a manager "
                                    + "promotes themselves to owner", role)
                            .isEqualTo(403);

                    softly.assertThat(deleteAs("/users/" + ownerId, token))
                            .as("%s must not be able to deactivate a user — otherwise a manager "
                                    + "disables every owner", role)
                            .isEqualTo(403);
                }
            });
        }

        @Test
        @DisplayName("owner may invite, patch and deactivate")
        void ownerMayMutate() {
            // The allow half. Without it, every deny assertion above would pass
            // just as well against an endpoint that refuses everyone — including
            // one that is simply broken.
            String owner = tokenFor("owner");
            String email = "invited-" + UUID.randomUUID() + "@example.test";

            var invited = http().postWithToken("/users",
                    json("email", email, "fullName", "Ingrid Invitee", "role", "clerk"), owner);

            assertThat(invited.status()).as("owner must be able to invite").isEqualTo(201);
            assertThat(invited.at("/status"))
                    .as("a new invitee starts as invited, not active")
                    .isEqualTo("invited");
            assertThat(invited.at("/role")).isEqualTo("clerk");

            UUID invitedId = UUID.fromString(invited.at("/id"));

            assertThat(patchAs("/users/" + invitedId, json("role", "manager"), owner))
                    .as("owner must be able to change a role")
                    .isEqualTo(200);

            assertThat(deleteAs("/users/" + invitedId, owner))
                    .as("owner must be able to deactivate")
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a user id from another tenant is 404, never 403")
        void anotherTenantsUserIsNotFound() {
            // T8. RLS hides the row, so the lookup genuinely finds nothing and
            // the 404 falls out rather than having to be remembered — but a
            // future refactor could reintroduce a 403, which is why this asserts.
            assertThat(patchAs("/users/" + UUID.randomUUID(), json("role", "viewer"),
                    tokenFor("owner")))
                    .as("a 403 here would confirm the id exists in some tenant")
                    .isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------
    // Plumbing — the JDK client only does GET and POST
    // ------------------------------------------------------------------

    private int postAs(String path, String body, String token) {
        return send("POST", path, body, token);
    }

    private int patchAs(String path, String body, String token) {
        return send("PATCH", path, body, token);
    }

    private int deleteAs(String path, String token) {
        return send("DELETE", path, null, token);
    }

    private int send(String method, String path, String body, String token) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .method(method, body == null
                            ? java.net.http.HttpRequest.BodyPublishers.noBody()
                            : java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("request failed", e);
        }
    }
}
