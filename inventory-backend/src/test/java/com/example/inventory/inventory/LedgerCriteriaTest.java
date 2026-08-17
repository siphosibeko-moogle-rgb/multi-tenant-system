package com.example.inventory.inventory;

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
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M2 acceptance criteria that were only covered incidentally, asserted
 * directly.
 *
 * <p>Three of the milestone's "Done when" items were true of the code but not
 * actually the subject of any test:
 *
 * <ul>
 *   <li>"a receipt then a sale leaves {@code product_stock} at the correct
 *       balance" — happened inside {@code ConcurrentOversellTest}, as setup for
 *       something else. A criterion asserted only as a side effect of another
 *       test disappears the moment that test is rewritten.</li>
 *   <li>"rebuilding {@code product_stock} from {@code SUM(quantity_delta)}
 *       matches the cache" — likewise.</li>
 *   <li>"UPDATE/DELETE on {@code stock_movements} raises <em>from the
 *       trigger</em>" — <strong>was not covered at all.</strong> The existing
 *       test attempts both as the application role, which V2 has revoked
 *       UPDATE and DELETE from, so PostgreSQL refuses on privilege before the
 *       trigger is ever consulted. It proved the grant, not the trigger.</li>
 * </ul>
 */
@DisplayName("M2 ledger criteria")
class LedgerCriteriaTest extends AbstractIntegrationTest {

    @Autowired
    private StockLedgerService ledger;

    private static UUID tenantId;
    private static UUID locationId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        locationId = UUID.randomUUID();
        productId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'crit-%s', 'Shop')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name)
                    VALUES ('%s', '%s', 'SKU-CRIT', 'Criterion Widget')
                    """.formatted(productId, tenantId));
        }
        seeded = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, null, null));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private JdbcTemplate ownerJdbc(Connection conn) {
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn, true));
    }

    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return ownerJdbc(conn).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a receipt then a sale leaves product_stock at the correct balance")
    void receiptThenSaleLeavesTheCorrectBalance() throws Exception {
        BigDecimal before = readAsOwner("""
                SELECT COALESCE((SELECT quantity_on_hand FROM product_stock
                                  WHERE product_id = ? AND location_id = ?), 0)
                """, BigDecimal.class, productId, locationId);

        var receipt = asTenant(() -> ledger.post(new MovementRequest(
                productId, locationId, "purchase_receipt", new BigDecimal("12"),
                new BigDecimal("4.50"), null, null, "delivery", null)));
        assertThat(receipt.balanceAfter())
                .as("the receipt's reported balance must be the one it produced")
                .isEqualByComparingTo(before.add(new BigDecimal("12")));

        var sale = asTenant(() -> ledger.post(new MovementRequest(
                productId, locationId, "sale", new BigDecimal("-5"), null,
                "sale", UUID.randomUUID(), null, null)));

        BigDecimal expected = before.add(new BigDecimal("12")).subtract(new BigDecimal("5"));

        assertThat(sale.balanceAfter())
                .as("12 in then 5 out is 7 more than we started with")
                .isEqualByComparingTo(expected);

        assertThat(readAsOwner("""
                SELECT quantity_on_hand FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId))
                .as("and the cached balance must agree with what the service reported")
                .isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("rebuilding product_stock from SUM(quantity_delta) matches the cache exactly")
    void rebuildFromTheLedgerMatchesTheCache() throws Exception {
        // A spread of movement types and signs, so the reconciliation is over
        // something more interesting than one receipt.
        asTenant(() -> ledger.post(new MovementRequest(productId, locationId, "purchase_receipt",
                new BigDecimal("30"), new BigDecimal("4.50"), null, null, "bulk", null)));
        asTenant(() -> ledger.post(new MovementRequest(productId, locationId, "sale",
                new BigDecimal("-7"), null, "sale", UUID.randomUUID(), null, null)));
        asTenant(() -> ledger.post(new MovementRequest(productId, locationId, "adjustment",
                new BigDecimal("-2"), null, null, null, "damaged", null)));
        asTenant(() -> ledger.post(new MovementRequest(productId, locationId, "sale_return",
                new BigDecimal("3"), null, "return", UUID.randomUUID(), null, null)));

        BigDecimal cached = readAsOwner("""
                SELECT quantity_on_hand FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);

        BigDecimal rebuilt = readAsOwner("""
                SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);

        // The cache is derived data. It is only worth trusting because it can be
        // shown to equal the rows it was derived from — if these ever diverge,
        // the ledger is right and the cache is wrong.
        assertThat(rebuilt)
                .as("product_stock must equal SUM(quantity_delta) over the ledger")
                .isEqualByComparingTo(cached);

        assertThat(rebuilt)
                .as("and must be non-trivial, or this test would pass on an empty table")
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("UPDATE and DELETE on stock_movements raise FROM THE TRIGGER")
    void theTriggerRefusesMutationEvenForARoleThatIsAllowedTo() throws Exception {
        asTenant(() -> ledger.post(new MovementRequest(productId, locationId, "purchase_receipt",
                new BigDecimal("5"), null, null, null, "for the trigger", null)));

        long id = readAsOwner("SELECT max(id) FROM stock_movements WHERE product_id = ?",
                Long.class, productId);

        // As the OWNER, deliberately. The application role has UPDATE and DELETE
        // revoked by V2, so an attempt from there is refused on privilege and
        // never reaches the trigger — which is why the existing append-only test
        // proves the grant rather than the trigger. The owner is the container
        // superuser: it holds every privilege and bypasses RLS, so the trigger is
        // the only thing left that can say no.
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            JdbcTemplate owner = ownerJdbc(conn);

            assertThatThrownBy(() -> owner.update(
                    "UPDATE stock_movements SET quantity_delta = 999 WHERE id = ?", id))
                    .as("T4: the ledger is append-only, and the trigger is what enforces it")
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() -> owner.update(
                    "DELETE FROM stock_movements WHERE id = ?", id))
                    .as("deleting history is the same offence as rewriting it")
                    .hasMessageContaining("append-only");
        }

        assertThat(readAsOwner("SELECT quantity_delta FROM stock_movements WHERE id = ?",
                BigDecimal.class, id))
                .as("the row must be exactly as posted")
                .isEqualByComparingTo("5");
    }
}
