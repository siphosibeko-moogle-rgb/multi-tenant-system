package com.example.inventory.forecasting;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what on the forecasting endpoints — <strong>allow and deny, per
 * role, asserted both ways</strong>.
 *
 * <p>CLAUDE.md §13: a gate that accidentally permits everyone passes a
 * happy-path test perfectly, and a role's absence from that test is not evidence
 * it is refused. So every role is asked to do every thing and the answer is
 * checked in both directions, including a viewer attempting each write.
 *
 * <h2>The gates, and why</h2>
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Allowed</th><th>Why</th></tr>
 *   <tr><td>{@code GET /forecasts}</td><td>all four</td>
 *       <td>Reading. {@code viewer} is "read only", which grants reads.</td></tr>
 *   <tr><td>{@code GET /products/{id}/forecast}</td><td>all four</td>
 *       <td>Same.</td></tr>
 *   <tr><td>{@code GET /reorder-recommendations}</td><td>all four</td>
 *       <td>Same.</td></tr>
 *   <tr><td>{@code POST /forecasts/recompute}</td><td>owner, manager</td>
 *       <td>Writes rows, and feeds purchasing — the manager's remit. Not one of
 *           a clerk's three listed duties.</td></tr>
 *   <tr><td>{@code POST /.../dismiss}</td><td>owner, manager</td>
 *       <td>A purchasing decision: "we are not ordering this".</td></tr>
 * </table>
 *
 * <p>The contract's {@code UserRole} table is the source, and where it is silent
 * the narrower reading wins (CLAUDE.md §13). Widening either write gate later is
 * a one-line change; discovering a clerk had been dismissing the shop's reorder
 * advice is not.
 */
@DisplayName("Forecasting role gates")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForecastRoleTest extends AbstractIntegrationTest {

    private static final List<String> ALL_ROLES = List.of("owner", "manager", "clerk", "viewer");
    private static final List<String> WRITE_ROLES = List.of("owner", "manager");

    @LocalServerPort
    private int port;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;

    @Autowired
    private com.example.inventory.auth.AuthProperties authProperties;

    @Autowired
    private com.example.inventory.inventory.StockLedgerService ledger;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private static SeededTenant tenant;
    private static UUID openRecommendationId;
    private static boolean prepared;

    @BeforeEach
    void seed() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // A short window: this class is about authorisation, not about demand
        // shapes, and it needs a product and a recommendation rather than 30
        // weeks of realistic history.
        tenant = seeder.seedTenant("Role Bakery", "role-" + tag, 1L, 10);
        asTenant(() -> {
            rollup.rollUp();
            // M6 keeps every product comfortably stocked, so an untouched seed
            // produces no recommendations at all — and a dismiss test with
            // nothing to dismiss returns 404 for every role, which would make
            // the deny cases pass for entirely the wrong reason. Write the
            // steady seller down so there is a real row behind the gate.
            //
            // Down TO a level, not BY an amount: a fixed decrement large enough
            // to be sure is also large enough to drive the balance negative, and
            // the ledger trigger refuses that outright (T12) — correctly, and
            // fatally for the fixture.
            java.math.BigDecimal onHand = new org.springframework.jdbc.core.JdbcTemplate(
                    appDataSource).queryForObject("""
                            SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                            WHERE product_id = ? AND location_id = ?
                            """, java.math.BigDecimal.class,
                    tenant.productIdsByShape().get("steady"), tenant.locationId());
            ledger.post(new com.example.inventory.inventory.StockLedgerService.MovementRequest(
                    tenant.productIdsByShape().get("steady"), tenant.locationId(),
                    "adjustment", java.math.BigDecimal.ONE.subtract(onHand), null, null, null,
                    "role fixture write-off",
                    java.time.LocalDate.now().atTime(14, 0)
                            .atOffset(java.time.ZoneOffset.UTC)));
            return reorderService.recomputeAll();
        });
        prepared = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String tokenFor(String role) {
        return jwtService.issueAccessToken(tenant.tenantId(), tenant.ownerId(), role).value();
    }

    /** An id that exists, so a 403 cannot be confused with a 404. */
    private String someRecommendationId() {
        if (openRecommendationId == null) {
            var items = http().get("/reorder-recommendations", tokenFor("owner"))
                    .json().get("items");
            openRecommendationId = items.isEmpty() ? UUID.randomUUID()
                    : UUID.fromString(items.get(0).get("id").asString());
        }
        return openRecommendationId.toString();
    }

    private static boolean isAllowed(int status) {
        return status != 403;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every role may read forecasts, including viewer")
    void readingForecastsIsOpenToEveryRole() {
        SoftAssertions.assertSoftly(softly -> {
            for (String role : ALL_ROLES) {
                softly.assertThat(http().get("/forecasts", tokenFor(role)).status())
                        .as("%s reading /forecasts", role)
                        .isEqualTo(200);
                softly.assertThat(http().get(
                        "/products/" + tenant.productIdsByShape().get("steady") + "/forecast",
                        tokenFor(role)).status())
                        .as("%s reading one product's forecast", role)
                        .isEqualTo(200);
                softly.assertThat(http().get("/reorder-recommendations", tokenFor(role)).status())
                        .as("%s reading the reorder list", role)
                        .isEqualTo(200);
            }
        });
    }

    @Test
    @DisplayName("only owner and manager may recompute — clerk and viewer are refused")
    void recomputeIsGated() {
        SoftAssertions.assertSoftly(softly -> {
            for (String role : ALL_ROLES) {
                int status = http().postWithToken("/forecasts/recompute", "{}", tokenFor(role))
                        .status();
                if (WRITE_ROLES.contains(role)) {
                    softly.assertThat(status).as("%s must be allowed to recompute", role)
                            .isEqualTo(202);
                } else {
                    softly.assertThat(status)
                            .as("%s must be refused — a gate that accidentally permits "
                                    + "everyone passes a happy-path test perfectly", role)
                            .isEqualTo(403);
                }
            }
        });
    }

    @Test
    @DisplayName("only owner and manager may dismiss — clerk and viewer are refused")
    void dismissIsGated() {
        SoftAssertions.assertSoftly(softly -> {
            for (String role : List.of("clerk", "viewer")) {
                softly.assertThat(http().postWithToken(
                        "/reorder-recommendations/" + someRecommendationId() + "/dismiss",
                        "{}", tokenFor(role)).status())
                        .as("%s must not be able to dismiss the shop's reorder advice", role)
                        .isEqualTo(403);
            }
        });

        // The positive twin, last so the deny cases run against a live row:
        // without it, a gate that refused EVERYONE would satisfy every
        // assertion above.
        assertThat(http().postWithToken(
                "/reorder-recommendations/" + someRecommendationId() + "/dismiss",
                "{}", tokenFor("manager")).status())
                .as("a manager must be able to — purchasing is the manager's remit")
                .isEqualTo(204);
    }

    @Test
    @DisplayName("a token with no role claim is refused everywhere, not defaulted")
    void aClaimlessTokenIsRefused() {
        // Built through the encoder rather than JwtService, because
        // JwtClaimsSet rejects a null claim value — there is no way to ask the
        // service for a token it would never legitimately issue. Same approach
        // as RoleEnforcementTest's.
        java.time.Instant now = java.time.Instant.now();
        String claimless = jwtEncoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(
                                com.example.inventory.auth.AuthProperties.SIGNING_ALGORITHM)
                                .build(),
                        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                                .issuer(authProperties.issuer())
                                .issuedAt(now)
                                .expiresAt(now.plusSeconds(300))
                                .subject(tenant.ownerId().toString())
                                .id(UUID.randomUUID().toString())
                                .claim("tid", tenant.tenantId().toString())
                                .build())).getTokenValue();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(isAllowed(http().get("/forecasts", claimless).status()))
                    .as("no role must mean no access, not the most permissive one")
                    .isFalse();
            softly.assertThat(isAllowed(http().postWithToken(
                    "/forecasts/recompute", "{}", claimless).status())).isFalse();
            softly.assertThat(isAllowed(http().get("/reorder-recommendations", claimless)
                    .status())).isFalse();
        });
    }

    @Test
    @DisplayName("an unauthenticated request is 401 with a problem body, not 403")
    void unauthenticatedIsRefusedBeforeTheRoleGate() {
        HttpTestClient.Response response = http().get("/forecasts", null);

        assertThat(response.status())
                .as("no credentials is an authentication problem, not an authorisation one — "
                        + "and the client's correct response differs (log in vs give up)")
                .isEqualTo(401);
        assertThat(response.headers().first("Content-Type"))
                .as("M3 fixed a bodiless 401; the contract declares a Problem here")
                .startsWith("application/problem+json");
    }
}
