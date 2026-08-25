package com.example.inventory.reporting;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

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
 * Who may read a report — asserted from <strong>both</strong> sides.
 *
 * <p>CLAUDE.md §13: a gate that accidentally permits everyone passes a happy-path
 * test perfectly, and a role's absence from that test is not evidence it is
 * refused. So every role is asked to do every thing and the answer is asserted
 * either way.
 *
 * <h2>Owner and manager, nobody else</h2>
 *
 * <p>The contract's {@code UserRole} table lists "reports" under manager and
 * nowhere else, and gives owner everything. Clerk's three jobs are "record
 * sales, receive stock, count". Viewer is "read only", which grants a mode and
 * says nothing about scope.
 *
 * <p>The substantive reason, rather than only the textual one: <strong>every one
 * of these four responses carries cost or margin</strong> — {@code grossProfit},
 * {@code inventoryValue} at cost, {@code totalCostValue}, {@code grossMarginPct}.
 * There is no honest redacted variant, because {@code grossProfit} and
 * {@code inventoryValue} are both in {@code DashboardSummary}'s {@code required}
 * list and omitting them breaks every generated client.
 *
 * <p>Viewer is the gate most likely to want widening later — see
 * {@code docs/adr/reporting.md} §5, which says how, and why it is left narrow.
 */
@DisplayName("Report role gates")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportRoleTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static ReportFixture shop;

    @BeforeEach
    void seedOnce() throws SQLException {
        if (shop == null) {
            shop = ReportFixture.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword(), http(),
                    (tenantId, userId) -> jwtService
                            .issueAccessToken(tenantId, userId, "owner").value());
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String tokenFor(String role) {
        return jwtService.issueAccessToken(shop.tenantId, shop.userId, role).value();
    }

    /** All four endpoints, each with the parameters that make it a valid request. */
    private static List<String> endpoints() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return List.of(
                "/reports/dashboard",
                "/reports/sales-summary?from=%s&to=%s".formatted(today.minusDays(6), today),
                "/reports/inventory-valuation",
                "/reports/top-products");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("an owner may read every report")
    void ownerIsAllowed() {
        for (String endpoint : endpoints()) {
            assertThat(http().get(endpoint, tokenFor("owner")).status())
                    .as("owner on %s", endpoint)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a manager may read every report — the contract names reports as theirs")
    void managerIsAllowed() {
        for (String endpoint : endpoints()) {
            assertThat(http().get(endpoint, tokenFor("manager")).status())
                    .as("manager on %s", endpoint)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a clerk is refused every report")
    void clerkIsRefused() {
        for (String endpoint : endpoints()) {
            HttpTestClient.Response response = http().get(endpoint, tokenFor("clerk"));

            assertThat(response.status())
                    .as("clerk on %s — a clerk records sales; margin is not one of "
                            + "their three jobs", endpoint)
                    .isEqualTo(403);
            assertThat(response.body())
                    .as("a 403 with no body cannot be reported to a user")
                    .isNotBlank();
            assertThat(response.headers().first("Content-Type"))
                    .startsWith("application/problem+json");
        }
    }

    @Test
    @DisplayName("a viewer is refused every report")
    void viewerIsRefused() {
        for (String endpoint : endpoints()) {
            assertThat(http().get(endpoint, tokenFor("viewer")).status())
                    .as("viewer on %s — \"read only\" grants a mode, not a scope, and "
                            + "these bodies disclose cost and margin", endpoint)
                    .isEqualTo(403);
        }
    }

    // A token carrying NO role claim is refused rather than defaulted — asserted
    // once, in RoleEnforcementTest.aRolelessTokenIsRefused, because that is a
    // property of SecurityConfig's authorities converter and not of these
    // endpoints. Restating it here would need the raw JwtEncoder (JwtClaimsSet
    // rejects a null claim, so JwtService cannot issue one) and would test the
    // same single mechanism a second time.

    @Test
    @DisplayName("an unauthenticated request is 401 with a problem body")
    void unauthenticatedIsRefused() {
        for (String endpoint : endpoints()) {
            HttpTestClient.Response response = http().get(endpoint, null);

            assertThat(response.status()).as("anonymous on %s", endpoint).isEqualTo(401);
            assertThat(response.headers().first("Content-Type"))
                    .startsWith("application/problem+json");
        }
    }

    /**
     * The refusals above must be about the <em>role</em>, not about a broken
     * fixture — a tenant with no data would answer 200 to an owner too, and
     * every deny assertion would still pass while proving nothing about the
     * allow side.
     */
    @Test
    @DisplayName("the reports an owner is allowed to read are not empty")
    void theAllowedCaseIsNotVacuous() {
        assertThat(http().get("/reports/dashboard", tokenFor("owner")).json()
                .get("salesTotal").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(http().get("/reports/top-products", tokenFor("manager")).json().size())
                .isGreaterThan(0);
    }
}
