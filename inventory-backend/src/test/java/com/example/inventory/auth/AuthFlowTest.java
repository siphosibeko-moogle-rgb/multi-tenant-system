package com.example.inventory.auth;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;

import static com.example.inventory.auth.HttpTestClient.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over real HTTP: register, log in, call {@code /me}, rotate, replay,
 * log out.
 *
 * <p>Real requests rather than MockMvc — see {@link HttpTestClient} for why. It
 * matters here specifically: several of these assertions are about responses
 * Spring Security writes itself, before any controller is reached.
 */
@DisplayName("Auth flow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TenantRegistrationService registrationService;

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private record Registered(String slug, String email, String password, AuthTokens tokens) {
    }

    private Registered register() {
        String slug = "flow-" + UUID.randomUUID().toString().substring(0, 8);
        String email = slug + "@example.test";
        String password = "correct-horse-battery";
        AuthTokens tokens = registrationService.register(new TenantRegistrationRequest(
                "Flow Trading", slug, email, password, "Ada Owner", "ZAR", "UTC"));
        return new Registered(slug, email, password, tokens);
    }

    // ------------------------------------------------------------------
    // Access tokens
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /me returns the caller's own tenant, resolved from the token")
    void meReturnsTheTokenHolder() {
        Registered account = register();

        var response = http().get("/me", account.tokens().accessToken());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/email")).isEqualTo(account.email());
        assertThat(response.at("/role")).isEqualTo("owner");
        assertThat(response.at("/tenant/slug")).isEqualTo(account.slug());
    }

    @Test
    @DisplayName("GET /me without a token is 401, not 200 with an empty body")
    void meRequiresTheToken() {
        assertThat(http().get("/me", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected")
    void aForgedTokenIsRejected() {
        // Well-formed, plausible claims, wrong signature. This is the forgery the
        // whole isolation story depends on failing: a valid-looking tid gets a
        // caller nowhere unless the signature verifies.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJ0aWQiOiIwMDAwMDAw"
                + "MC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDIiLCJyb2xlIjoib3duZXIifQ."
                + "ZmFrZS1zaWduYXR1cmUtdGhhdC13aWxsLW5vdC12ZXJpZnk";

        assertThat(http().get("/me", forged).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a refresh token is not accepted as an access token")
    void aRefreshTokenIsNotAnAccessToken() {
        Registered account = register();

        // Correctly signed by the same key, so only the typ claim separates them.
        // Writing this test is what revealed that nothing checked it: the resource
        // server accepted the refresh token happily, which would have made a
        // 30-day at-rest credential work everywhere a 15-minute one does.
        // SecurityConfig now rejects typ=refresh and RefreshTokenService makes the
        // mirror-image check, so neither kind substitutes for the other.
        assertThat(http().get("/me", account.tokens().refreshToken()).status())
                .as("a refresh token must not authenticate an ordinary request")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("returns a token pair for the right password")
        void loginSucceeds() {
            Registered account = register();

            var response = http().post("/auth/login", json(
                    "email", account.email(),
                    "password", account.password(),
                    "tenantSlug", account.slug(),
                    "deviceLabel", "junit"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.at("/accessToken")).isNotBlank();
            assertThat(response.at("/user/tenant/slug")).isEqualTo(account.slug());
        }

        @Test
        @DisplayName("a wrong password and an unknown email are indistinguishable")
        void wrongPasswordAndUnknownEmailLookTheSame() {
            Registered account = register();

            var wrongPassword = http().post("/auth/login", json(
                    "email", account.email(), "password", "not-the-right-password"));
            var unknownEmail = http().post("/auth/login", json(
                    "email", "nobody-" + UUID.randomUUID() + "@example.test",
                    "password", "not-the-right-password"));

            assertThat(wrongPassword.status()).isEqualTo(401);
            assertThat(unknownEmail.status()).isEqualTo(401);
            assertThat(wrongPassword.body())
                    .as("byte-identical, or the endpoint is an account enumerator")
                    .isEqualTo(unknownEmail.body());
        }

        @Test
        @DisplayName("errors come back as problem+json, never a stack trace")
        void failuresAreProblemDetails() {
            var response = http().post("/auth/login", json(
                    "email", "nobody-" + UUID.randomUUID() + "@example.test",
                    "password", "wrong-password"));

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.headers().first("Content-Type"))
                    .as("RFC 9457, one shape everywhere")
                    .contains("application/problem+json");
            assertThat(response.at("/title")).isNotBlank();
            assertThat(response.at("/type")).isNotBlank();
            assertThat(response.body())
                    .as("never a stack trace")
                    .doesNotContain("Exception").doesNotContain("at com.example");
        }

        @Test
        @DisplayName("an email in two tenants returns 300 with the choices")
        void oneEmailInTwoTenantsAsksWhich() {
            String email = "shared-" + UUID.randomUUID() + "@example.test";
            String slugA = "multi-a-" + UUID.randomUUID().toString().substring(0, 6);
            String slugB = "multi-b-" + UUID.randomUUID().toString().substring(0, 6);

            registrationService.register(new TenantRegistrationRequest(
                    "First Business", slugA, email, "correct-horse-battery", "Ada", "ZAR", "UTC"));
            registrationService.register(new TenantRegistrationRequest(
                    "Second Business", slugB, email, "correct-horse-battery", "Ada", "ZAR", "UTC"));

            var ambiguous = http().post("/auth/login", json(
                    "email", email, "password", "correct-horse-battery"));

            assertThat(ambiguous.status()).isEqualTo(300);
            assertThat(ambiguous.json().at("/tenants").size()).isEqualTo(2);

            // ...and naming one resolves it.
            var resolved = http().post("/auth/login", json(
                    "email", email, "password", "correct-horse-battery", "tenantSlug", slugA));

            assertThat(resolved.status()).isEqualTo(200);
            assertThat(resolved.at("/user/tenant/slug")).isEqualTo(slugA);
        }
    }

    // ------------------------------------------------------------------
    // Refresh rotation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Refresh rotation")
    class Rotation {

        private String rotate(String token) {
            var response = http().post("/auth/refresh", json("refreshToken", token));
            assertThat(response.status()).isEqualTo(200);
            return response.at("/refreshToken");
        }

        @Test
        @DisplayName("rotates to a new token, and the old one stops working")
        void refreshRotatesAndInvalidatesTheOldToken() {
            Registered account = register();
            String original = account.tokens().refreshToken();

            String rotated = rotate(original);
            assertThat(rotated).isNotEqualTo(original);

            assertThat(http().post("/auth/refresh", json("refreshToken", original)).status())
                    .as("single use: the old token dies at rotation")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("replaying a used token revokes the whole family")
        void replayRevokesEverything() {
            Registered account = register();
            String first = account.tokens().refreshToken();
            String second = rotate(first);

            // The replay. Indistinguishable from theft, so it is treated as theft.
            assertThat(http().post("/auth/refresh", json("refreshToken", first)).status())
                    .isEqualTo(401);

            // The still-live token from the legitimate client is collateral —
            // deliberately. Costing a real user a re-login is the right trade
            // against leaving a thief with a working credential.
            assertThat(http().post("/auth/refresh", json("refreshToken", second)).status())
                    .as("the replay must invalidate the live token too, not just the replayed one")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("logout revokes the presented token and stays 204 either way")
        void logoutRevokes() {
            Registered account = register();
            String refresh = account.tokens().refreshToken();
            String access = account.tokens().accessToken();

            assertThat(http().postWithToken("/auth/logout", json("refreshToken", refresh), access)
                    .status()).isEqualTo(204);
            assertThat(http().post("/auth/refresh", json("refreshToken", refresh)).status())
                    .isEqualTo(401);

            // Logging out twice must not report that the token was already gone:
            // that would make logout an oracle for whether a stolen token is live.
            assertThat(http().postWithToken("/auth/logout", json("refreshToken", refresh), access)
                    .status()).isEqualTo(204);
        }

        @Test
        @DisplayName("logout requires an access token, as the contract says")
        void logoutIsAuthenticated() {
            Registered account = register();

            // The contract marks register-tenant, login and refresh with
            // `security: []` and leaves logout on the default bearerAuth. M1
            // originally had it open, which contradicted that. Asserted here so
            // the reconciliation cannot quietly regress.
            assertThat(http().post("/auth/logout",
                    json("refreshToken", account.tokens().refreshToken())).status())
                    .as("logout without a token must be refused")
                    .isEqualTo(401);

            // ...and the token is still live afterwards, i.e. the rejected call
            // did nothing.
            assertThat(http().post("/auth/refresh",
                    json("refreshToken", account.tokens().refreshToken())).status())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a bodyless logout ends every session for the user")
        void bodylessLogoutEndsEverySession() {
            Registered account = register();

            // Two live sessions: the one from registration, and one from a login.
            var loggedIn = http().post("/auth/login", json(
                    "email", account.email(), "password", account.password(),
                    "tenantSlug", account.slug()));
            assertThat(loggedIn.status()).isEqualTo(200);
            String secondSession = loggedIn.at("/refreshToken");

            assertThat(http().postWithToken("/auth/logout", "{}",
                    account.tokens().accessToken()).status()).isEqualTo(204);

            assertThat(http().post("/auth/refresh",
                    json("refreshToken", account.tokens().refreshToken())).status())
                    .as("sign out everywhere means everywhere")
                    .isEqualTo(401);
            assertThat(http().post("/auth/refresh", json("refreshToken", secondSession)).status())
                    .as("including sessions the caller did not name")
                    .isEqualTo(401);
        }
    }

    // ------------------------------------------------------------------
    // Cross-tenant reach
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two tenants' tokens see entirely different accounts")
    void twoTenantsSeeDifferentThings() {
        Registered a = register();
        Registered b = register();

        assertThat(http().get("/me", a.tokens().accessToken()).at("/tenant/slug"))
                .isEqualTo(a.slug());
        assertThat(http().get("/me", b.tokens().accessToken()).at("/tenant/slug"))
                .isEqualTo(b.slug());

        assertThat(a.tokens().user().tenant().id())
                .isNotEqualTo(b.tokens().user().tenant().id());
    }
}
