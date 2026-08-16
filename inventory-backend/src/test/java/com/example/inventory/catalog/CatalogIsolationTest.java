package com.example.inventory.catalog;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two tenants holding the <strong>same SKU and the same barcode at the same
 * time</strong>, with lookup returning only the caller's.
 *
 * <h2>Why this needs its own test</h2>
 *
 * <p>V1 makes both indexes per tenant: {@code (tenant_id, upper(sku))} and
 * {@code (tenant_id, barcode)}. That is not incidental — barcodes are issued by
 * manufacturers, so every shop stocking the same can of beans holds the same
 * barcode, and SKUs are chosen locally so collisions between businesses are
 * routine.
 *
 * <p>A plausible-looking change to a <em>global</em> unique index would break
 * this, and here is the part that makes it worth a dedicated test: it would
 * <strong>not</strong> show up in {@code TenantIsolationTest}. Nothing would
 * leak. Tenant A would still see only its own rows. The failure would appear as
 * the second business to stock a popular product being unable to create it —
 * a 409 that looks like the customer's mistake, discovered in support rather
 * than in CI.
 *
 * <p>So the isolation sweep asserts that tenants cannot see each other's rows;
 * this asserts that they are allowed to hold the same <em>values</em>. Those are
 * different properties and only one of them is covered by counting rows.
 */
@DisplayName("Catalog isolation and per-tenant uniqueness")
class CatalogIsolationTest extends AbstractIntegrationTest {

    /** Deliberately identical for both tenants. */
    private static final String SHARED_SKU = "SKU-BEANS-400G";
    private static final String SHARED_BARCODE = "6001234567890";

    @Autowired
    private ProductCatalog catalog;

    private static UUID tenantA;
    private static UUID tenantB;
    private static boolean seeded;

    @BeforeEach
    void seedTwoTenants() throws SQLException {
        if (seeded) {
            return;
        }
        tenantA = newTenantId();
        tenantB = newTenantId();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            for (UUID tenant : new UUID[] {tenantA, tenantB}) {
                stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'cat-%s', 'Shop')"
                        .formatted(tenant, tenant.toString().substring(0, 8)));
            }
        }
        seeded = true;
    }

    private <T> T asTenant(UUID tenant, Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(tenant, null, null));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private static ProductWriteRequest beans(String name) {
        return new ProductWriteRequest(SHARED_SKU, SHARED_BARCODE, name, null, null, "each",
                new BigDecimal("8.00"), new BigDecimal("12.99"), new BigDecimal("0.15"),
                null, null, null, true, false, null);
    }

    private long countAsOwner(String sql, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true))
                    .queryForObject(sql, Long.class, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("both tenants hold the same SKU and barcode simultaneously")
    void twoTenantsMayHoldTheSameSkuAndBarcode() throws Exception {
        Versioned<Product> inA = asTenant(tenantA, () -> catalog.create(beans("Beans (A's listing)")));
        Versioned<Product> inB = asTenant(tenantB, () -> catalog.create(beans("Beans (B's listing)")));

        assertThat(inA.value().id()).isNotEqualTo(inB.value().id());
        assertThat(inA.value().sku()).isEqualTo(inB.value().sku()).isEqualTo(SHARED_SKU);
        assertThat(inA.value().barcode()).isEqualTo(inB.value().barcode()).isEqualTo(SHARED_BARCODE);

        // Both rows genuinely coexist. Read as the owner (superuser, so RLS does
        // not filter) because the point is what the TABLE holds, not what either
        // tenant can see.
        assertThat(countAsOwner("SELECT count(*) FROM products WHERE sku = ? AND barcode = ?",
                SHARED_SKU, SHARED_BARCODE))
                .as("a global unique index would have refused the second insert — and would not "
                        + "have leaked anything, so the isolation sweep would still be green")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("barcode lookup returns only the caller's product")
    void barcodeLookupReturnsOnlyTheCallersProduct() throws Exception {
        Versioned<Product> inA = asTenant(tenantA, () -> catalog.create(
                new ProductWriteRequest("SKU-LOOKUP", "7001112223334", "A's Cola", null, null,
                        "each", new BigDecimal("5.00"), new BigDecimal("9.99"),
                        new BigDecimal("0.15"), null, null, null, true, false, null)));
        Versioned<Product> inB = asTenant(tenantB, () -> catalog.create(
                new ProductWriteRequest("SKU-LOOKUP", "7001112223334", "B's Cola", null, null,
                        "each", new BigDecimal("6.00"), new BigDecimal("10.99"),
                        new BigDecimal("0.15"), null, null, null, true, false, null)));

        var foundByA = asTenant(tenantA, () -> catalog.findByBarcode("7001112223334"));
        var foundByB = asTenant(tenantB, () -> catalog.findByBarcode("7001112223334"));

        assertThat(foundByA).isPresent();
        assertThat(foundByB).isPresent();

        assertThat(foundByA.get().value().id())
                .as("A must get A's product — the query has no tenant predicate, so this is RLS "
                        + "choosing the row, not the SQL")
                .isEqualTo(inA.value().id());
        assertThat(foundByA.get().value().name()).isEqualTo("A's Cola");

        assertThat(foundByB.get().value().id())
                .as("and B must get B's, from the identical query")
                .isEqualTo(inB.value().id());
        assertThat(foundByB.get().value().name()).isEqualTo("B's Cola");

        assertThat(foundByA.get().value().id()).isNotEqualTo(foundByB.get().value().id());
    }

    @Test
    @DisplayName("within one tenant the SKU is still unique")
    void withinOneTenantTheSkuIsUnique() throws Exception {
        asTenant(tenantA, () -> catalog.create(
                new ProductWriteRequest("SKU-UNIQUE-A", null, "First", null, null, "each",
                        BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.15"),
                        null, null, null, true, false, null)));

        // The other half of the rule. Per-tenant uniqueness has to actually be
        // uniqueness, or this test would pass against no index at all.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asTenant(tenantA, () ->
                        catalog.create(new ProductWriteRequest("SKU-UNIQUE-A", null, "Duplicate",
                                null, null, "each", BigDecimal.ONE, BigDecimal.TEN,
                                new BigDecimal("0.15"), null, null, null, true, false, null))))
                .isInstanceOf(com.example.inventory.web.ConflictException.class)
                .hasMessageContaining("SKU");
    }

    @Test
    @DisplayName("a duplicate barcode within one tenant is reported as a barcode conflict")
    void duplicateBarcodeIsNamedAccurately() throws Exception {
        asTenant(tenantB, () -> catalog.create(
                new ProductWriteRequest("SKU-BC-1", "8001112223334", "First", null, null, "each",
                        BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.15"),
                        null, null, null, true, false, null)));

        // Different SKU, same barcode: the message must name the barcode, or the
        // user re-edits the SKU and the save fails again for no visible reason.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asTenant(tenantB, () ->
                        catalog.create(new ProductWriteRequest("SKU-BC-2", "8001112223334",
                                "Second", null, null, "each", BigDecimal.ONE, BigDecimal.TEN,
                                new BigDecimal("0.15"), null, null, null, true, false, null))))
                .isInstanceOf(com.example.inventory.web.ConflictException.class)
                .hasMessageContaining("barcode");
    }

    @Test
    @DisplayName("tenant A cannot read, update or delete B's product")
    void crossTenantAccessIsNotFound() throws Exception {
        Versioned<Product> inB = asTenant(tenantB, () -> catalog.create(
                new ProductWriteRequest("SKU-PRIVATE", null, "B's Secret", null, null, "each",
                        BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.15"),
                        null, null, null, true, false, null)));

        assertThat(asTenant(tenantA, () -> catalog.find(inB.value().id())))
                .as("invisible, so 404 rather than 403 (T8)")
                .isEmpty();
        assertThat(asTenant(tenantA, () -> catalog.deactivate(inB.value().id())))
                .as("and not deletable either")
                .isFalse();

        assertThat(countAsOwner("SELECT count(*) FROM products WHERE id = ? AND deleted_at IS NULL",
                inB.value().id()))
                .as("B's product must be untouched")
                .isEqualTo(1L);
    }
}
