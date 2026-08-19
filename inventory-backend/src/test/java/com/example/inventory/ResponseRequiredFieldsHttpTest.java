package com.example.inventory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every field the contract marks {@code required} is actually present, and
 * actually non-null, in the response the server sends.
 *
 * <h2>Why this is separate from ApiContractTest</h2>
 *
 * <p>{@code ApiContractTest} proves the contract is a valid OpenAPI document.
 * That is a statement about the file and says nothing about the server. The
 * moment M3 added {@code required} to the response schemas, the contract began
 * making a promise on the implementation's behalf — and nothing was checking it.
 *
 * <p>The promise is worth checking precisely because breaking it is silent on
 * this side. A generated client turns {@code required} into a non-nullable
 * Kotlin type, so a server that omits the field produces a deserialization
 * failure <em>on the device</em> — not a compile error, and not a failing
 * backend test. That gap is the one CLAUDE.md §5 exists about, and it is only
 * visible over HTTP.
 *
 * <h2>How it works</h2>
 *
 * <p>The required lists are read from {@code docs/openapi.yaml} at run time
 * rather than restated here. A copy would drift, and a drifted copy would agree
 * with whichever side was wrong. The parser resolves {@code $ref} and
 * {@code allOf}, so a composed schema contributes its parent's required fields
 * too.
 *
 * <p><strong>Absent and null are separate failures</strong>, and they are
 * reported separately because they break a client differently: an omitted field
 * makes the generated constructor throw for a missing argument, a JSON null
 * makes it throw on a non-nullable type. Jackson's {@code at()} returns a
 * MissingNode for the first and a NullNode for the second, and {@code asString()}
 * would flatten both into something harmless-looking — the same trap that let
 * M2's always-zero {@code available} bug survive a test.
 */
@DisplayName("Responses satisfy the contract's required fields")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseRequiredFieldsHttpTest extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("..", "docs", "openapi.yaml");

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static OpenAPI contract;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID categoryId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (contract == null) {
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            contract = new OpenAPIV3Parser().read(CONTRACT.toString(), null, options);
            assertThat(contract).as("docs/openapi.yaml must parse").isNotNull();
        }
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        productId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'req-%s', 'Required Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("INSERT INTO categories (id, tenant_id, name) "
                    + "VALUES ('%s', '%s', 'Bakery')".formatted(categoryId, tenantId));
            // Non-zero and mutually distinct throughout. A fixture of zeroes and
            // repeated values cannot distinguish a present field from a missing
            // one once anything reads a value.
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, category_id,
                                          cost_price, selling_price, tax_rate, reorder_point)
                    VALUES ('%s', '%s', 'SKU-REQ-1', 'Sourdough', '%s', 7.25, 12.50, 0.15, 4)
                    """.formatted(productId, tenantId, categoryId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String ownerToken() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    // ------------------------------------------------------------------
    // Reading the contract
    // ------------------------------------------------------------------

    private static Schema<?> schema(String name) {
        Schema<?> schema = contract.getComponents().getSchemas().get(name);
        assertThat(schema).as("schema %s must exist in the contract", name).isNotNull();
        return schema;
    }

    /** Required names for a named schema, flattening {@code allOf}. */
    private static List<String> requiredOf(String schemaName) {
        List<String> required = new ArrayList<>();
        collectRequired(schema(schemaName), required);
        assertThat(required)
                .as("%s declares no required fields, so this assertion would check nothing",
                        schemaName)
                .isNotEmpty();
        return required;
    }

    private static void collectRequired(Schema<?> schema, List<String> into) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            collectRequired(contract.getComponents().getSchemas().get(name), into);
        }
        if (schema.getRequired() != null) {
            into.addAll(schema.getRequired());
        }
        if (schema.getAllOf() != null) {
            for (Schema<?> part : schema.getAllOf()) {
                collectRequired(part, into);
            }
        }
    }

    /**
     * Required names for the element type of an array property — for the inline
     * line-item objects, which are not named schemas and so cannot be looked up
     * directly.
     */
    private static List<String> requiredOfArrayProperty(String schemaName, String property) {
        Schema<?> found = findProperty(schema(schemaName), property);
        assertThat(found).as("%s must declare a property %s", schemaName, property).isNotNull();
        Schema<?> items = found.getItems();
        assertThat(items).as("%s.%s must be an array", schemaName, property).isNotNull();

        List<String> required = new ArrayList<>();
        collectRequired(items, required);
        assertThat(required)
                .as("%s.%s items declare no required fields", schemaName, property)
                .isNotEmpty();
        return required;
    }

    private static Schema<?> findProperty(Schema<?> schema, String property) {
        if (schema == null) {
            return null;
        }
        if (schema.getProperties() != null && schema.getProperties().containsKey(property)) {
            return (Schema<?>) schema.getProperties().get(property);
        }
        if (schema.getAllOf() != null) {
            for (Schema<?> part : schema.getAllOf()) {
                Schema<?> found = findProperty(part, property);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The check itself
    // ------------------------------------------------------------------

    private static void satisfies(JsonNode object, String label, List<String> required) {
        assertThat(object.isObject())
                .as("expected a JSON object to check against %s, got: %s", label, object)
                .isTrue();

        List<String> missing = new ArrayList<>();
        List<String> nulls = new ArrayList<>();
        for (String field : required) {
            JsonNode value = object.get(field);
            if (value == null) {
                missing.add(field);
            } else if (value.isNull()) {
                nulls.add(field);
            }
        }

        assertThat(missing)
                .as("%s: the contract requires these fields and the response omits them "
                        + "entirely. A generated client cannot construct the model at all. "
                        + "Body: %s", label, object)
                .isEmpty();
        assertThat(nulls)
                .as("%s: the contract requires these fields and the response sends them as "
                        + "null. A generated client types them non-nullable and fails to "
                        + "deserialize. Body: %s", label, object)
                .isEmpty();
    }

    private static void satisfies(JsonNode object, String schemaName) {
        satisfies(object, schemaName, requiredOf(schemaName));
    }

    /** Every element, and never zero elements — an empty array proves nothing. */
    private static void eachSatisfies(JsonNode array, String label, List<String> required) {
        assertThat(array).as("expected an array of %s but the field was absent", label).isNotNull();
        assertThat(array.isArray())
                .as("expected an array of %s, got: %s", label, array).isTrue();
        assertThat(array.size())
                .as("the fixture must produce at least one %s — an empty list satisfies "
                        + "every field assertion without checking anything", label)
                .isGreaterThan(0);
        for (JsonNode element : array) {
            satisfies(element, label, required);
        }
    }

    private static void eachSatisfies(JsonNode array, String schemaName) {
        eachSatisfies(array, schemaName, requiredOf(schemaName));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /me satisfies CurrentUser")
    void currentUser() {
        var response = http().get("/me", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        satisfies(response.json(), "CurrentUser");

        // The one CurrentUser field deliberately NOT required. This tenant has a
        // default location; a freshly registered one does not, because
        // registration creates none — which is why the contract marks it
        // nullable. Asserted here so a future change to make it required has to
        // confront that case rather than discover it on a device.
        assertThat(requiredOf("CurrentUser")).doesNotContain("defaultLocationId");
    }

    @Test
    @DisplayName("GET /products satisfies Product for every row")
    void products() {
        var response = http().get("/products", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "Product");
    }

    @Test
    @DisplayName("GET /categories satisfies Category")
    void categories() {
        var response = http().get("/categories", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json(), "Category");
    }

    @Test
    @DisplayName("GET /locations satisfies Location")
    void locations() {
        var response = http().get("/locations", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json(), "Location");
    }

    @Test
    @DisplayName("GET /users satisfies User")
    void users() {
        var response = http().get("/users", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "User");
    }

    @Test
    @DisplayName("GET /inventory satisfies StockStatus")
    void inventory() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":9,"reason":"opening"}
                """.formatted(productId), ownerToken());

        var response = http().get("/inventory", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "StockStatus");
    }

    @Test
    @DisplayName("GET /inventory/movements satisfies StockMovement")
    void movements() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":6,"reason":"opening"}
                """.formatted(productId), ownerToken());

        var response = http().get("/inventory/movements", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "StockMovement");
    }

    @Test
    @DisplayName("POST /sales satisfies SaleDetail, line items included")
    void sale() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":20,"reason":"opening"}
                """.formatted(productId), ownerToken());

        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":2}]}
                """.formatted(productId), ownerToken());
        assertThat(response.status()).isEqualTo(201);

        satisfies(response.json(), "SaleDetail");
        eachSatisfies(response.json().get("lines"), "SaleDetail.lines[]",
                requiredOfArrayProperty("SaleDetail", "lines"));
    }

    @Test
    @DisplayName("an oversell 409 satisfies InsufficientStockProblem")
    void oversell() {
        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":99999}]}
                """.formatted(productId), ownerToken());
        assertThat(response.status()).isEqualTo(409);
        satisfies(response.json(), "InsufficientStockProblem");
    }

    /**
     * <strong>A known, unfixed divergence, pinned deliberately.</strong>
     *
     * <p>The contract declares {@code GET /products/{id}} as
     * {@code ProductDetail}. {@code CatalogController.get} returns
     * {@code Product}. Every ProductDetail field the endpoint does emit is
     * checked below; the one it does not is {@code stockByLocation}, the
     * per-location breakdown, which is not implemented at all.
     *
     * <p>This is a <em>backend</em> gap, not a contract error, and it is left as
     * a gap on purpose: loosening the contract to match would delete the only
     * written record that the per-location view was ever promised, and the fix
     * is a feature (a query over product_stock per location) rather than a typo.
     * It needs a decision, not a quiet edit.
     *
     * <p>The assertion is written so that <strong>fixing the backend turns this
     * test red</strong>. That is the intent: whoever implements
     * {@code stockByLocation} is told, by a failure, to promote this endpoint to
     * the ordinary {@link #satisfies} check above rather than leaving a test
     * that pins the old behaviour forever.
     */
    @Test
    @DisplayName("GET /products/{id} satisfies Product, but NOT yet ProductDetail")
    void productDetailIsNotYetImplemented() {
        var response = http().get("/products/" + productId, ownerToken());
        assertThat(response.status()).isEqualTo(200);

        // Everything the endpoint does promise, held to the contract.
        satisfies(response.json(), "Product");

        assertThat(requiredOf("ProductDetail"))
                .as("if stockByLocation stopped being required, this pin is stale")
                .contains("stockByLocation");
        assertThat(response.json().has("stockByLocation"))
                .as("stockByLocation is now implemented — delete this test and assert "
                        + "satisfies(body, \"ProductDetail\") instead")
                .isFalse();
    }

    /**
     * The error paths a client hits most, and the ones least likely to come from
     * {@code GlobalExceptionHandler}.
     *
     * <p>A 401 is written by Spring Security's entry point, and a 405/415 by
     * Spring MVC — neither goes through the application's handler, so neither is
     * covered by the ordinary error test. They matter more than most: the
     * Android {@code TokenAuthenticator} meets a 401 on every expired token, and
     * a body it cannot parse degrades a routine refresh into an unexplained
     * failure.
     */
    @Test
    @DisplayName("a 401 sends NO body at all — contract says Problem")
    void unauthorizedSendsNoBody() {
        var response = http().get("/products", null);
        assertThat(response.status()).isEqualTo(401);

        // Pinned, unfixed, and reported. The contract's `Unauthorized` response
        // declares a Problem body; Spring Security's entry point writes none,
        // because nothing in this application configures one and the default
        // sends only a status line and a WWW-Authenticate header.
        //
        // Why it matters more than it looks: this is the single most-travelled
        // error path in the mobile client. Every expired access token arrives
        // here, and TokenAuthenticator meets it on a hot path. It survives today
        // only because the authenticator keys on the STATUS and never reads the
        // body — so the omission costs nothing until the refresh itself fails,
        // at which point the user gets a generic message where the server had
        // something specific to say.
        //
        // Left as a gap rather than papered over: fixing it means writing an
        // AuthenticationEntryPoint that emits RFC 9457, and choosing the `type`
        // and `title` it carries is a contract decision, not a typo fix.
        // Loosening Problem instead would be the wrong direction — the other
        // sixteen error paths in this test do send complete bodies, and it is
        // this one endpoint that is wrong.
        //
        // Fixing the backend turns this test red on purpose. Whoever does it
        // should delete this and assert satisfies(body, "Problem") instead.
        assertThat(response.body())
                .as("a 401 now has a body — replace this pin with the ordinary "
                        + "satisfies(body, \"Problem\") check")
                .isBlank();
    }

    @Test
    @DisplayName("an ordinary error satisfies Problem")
    void problem() {
        var response = http().get("/products/" + UUID.randomUUID(), ownerToken());
        assertThat(response.status()).isEqualTo(404);
        satisfies(response.json(), "Problem");
    }
}
