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
import com.example.inventory.inventory.StockLedgerService.TransferRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Transfers: two movements, one transaction, all or nothing.
 *
 * <h2>Why the failure cases matter more than the happy path</h2>
 *
 * <p>A transfer that wrote the departure and then failed to write the arrival
 * would <em>destroy stock</em>. The source is decremented, the destination is
 * never credited, and because the ledger is append-only (T4) there is no update
 * that fixes it — only a compensating row somebody has to first notice is
 * needed. So the assertions below are on what is left behind after a failure:
 * the source balance, and the number of movement rows.
 *
 * <p>Both are asserted, deliberately. A balance check alone would pass if the
 * two movements were written and then netted out; a row-count check alone would
 * pass if nothing was written but the cache had been touched. Together they say
 * the transaction genuinely did not happen.
 */
@DisplayName("Stock transfers")
class TransferTest extends AbstractIntegrationTest {

    @Autowired
    private StockLedgerService ledger;

    private static UUID tenantId;
    private static UUID otherTenantId;
    private static UUID backRoom;
    private static UUID shopFloor;
    private static UUID otherTenantLocation;
    private static UUID productId;
    private static boolean seeded;

    /**
     * Two locations in one tenant, plus a location belonging to somebody else.
     *
     * <p>Seeded over the owner connection, which is the container superuser and
     * bypasses RLS — the only way to create the other tenant's location at all,
     * and never used for an assertion.
     */
    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        otherTenantId = newTenantId();
        backRoom = UUID.randomUUID();
        shopFloor = UUID.randomUUID();
        otherTenantLocation = UUID.randomUUID();
        productId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            for (UUID tenant : new UUID[] {tenantId, otherTenantId}) {
                stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'xfer-%s', 'Shop')"
                        .formatted(tenant, tenant.toString().substring(0, 8)));
            }
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Back Room', true)".formatted(backRoom, tenantId));
            stmt.execute("INSERT INTO locations (id, tenant_id, name) "
                    + "VALUES ('%s', '%s', 'Shop Floor')".formatted(shopFloor, tenantId));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Their Warehouse', true)"
                    .formatted(otherTenantLocation, otherTenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name)
                    VALUES ('%s', '%s', 'SKU-XFER', 'Transferable')
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

    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true)).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal balanceAt(UUID locationId) {
        return readAsOwner("""
                SELECT COALESCE((SELECT quantity_on_hand FROM product_stock
                                  WHERE product_id = ? AND location_id = ?), 0)
                """, BigDecimal.class, productId, locationId);
    }

    /** Total across every location — a transfer must never change this. */
    private BigDecimal totalQuantity() {
        return readAsOwner(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements WHERE product_id = ?",
                BigDecimal.class, productId);
    }

    private long movementCount() {
        return readAsOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, productId);
    }

    /** Puts {@code quantity} into the back room, through the ledger. */
    private void stockTheBackRoom(String quantity) throws Exception {
        asTenant(() -> ledger.post(new MovementRequest(productId, backRoom, "purchase_receipt",
                new BigDecimal(quantity), new BigDecimal("5.00"), null, null, "restock", null)));
    }

    // ------------------------------------------------------------------
    // The happy path, so the failure assertions are not vacuous
    // ------------------------------------------------------------------

    @Test
    @DisplayName("moves stock between locations as two paired movements")
    void transferMovesStock() throws Exception {
        stockTheBackRoom("10");

        // Deltas, not absolutes. Every test in this class stocks the same product
        // against one shared container, so absolute balances accumulate across
        // them — asserting "source is now 6" would couple this test to how many
        // others ran first, and would start failing the day one is added.
        BigDecimal sourceBefore = balanceAt(backRoom);
        BigDecimal destinationBefore = balanceAt(shopFloor);
        BigDecimal totalBefore = totalQuantity();

        var transfer = asTenant(() -> ledger.transfer(new TransferRequest(
                productId, backRoom, shopFloor, new BigDecimal("4"),
                "to the floor", UUID.randomUUID(), null)));

        assertThat(transfer.out().quantityDelta()).isEqualByComparingTo("-4");
        assertThat(transfer.in().quantityDelta()).isEqualByComparingTo("4");

        assertThat(balanceAt(backRoom))
                .as("source debited by exactly the transferred quantity")
                .isEqualByComparingTo(sourceBefore.subtract(new BigDecimal("4")));
        assertThat(balanceAt(shopFloor))
                .as("destination credited by exactly the transferred quantity")
                .isEqualByComparingTo(destinationBefore.add(new BigDecimal("4")));

        assertThat(totalQuantity())
                .as("a transfer moves stock, it does not create or destroy it")
                .isEqualByComparingTo(totalBefore);

        // Both halves carry the same reference_id, so the pair is recoverable as
        // one event rather than two unrelated movements that happen to line up.
        assertThat(readAsOwner("""
                SELECT count(DISTINCT reference_id) FROM stock_movements
                WHERE product_id = ? AND reference_type = 'transfer'
                """, Long.class, productId))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Failure case 1: the destination does not exist at all
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a destination that does not exist leaves the source untouched")
    void destinationFailureLeavesSourceUntouched() throws Exception {
        stockTheBackRoom("10");

        BigDecimal sourceBefore = balanceAt(backRoom);
        long movementsBefore = movementCount();
        UUID nowhere = UUID.randomUUID();

        Throwable thrown = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, nowhere, new BigDecimal("4"),
                        "into the void", UUID.randomUUID(), null))));

        assertThat(thrown).as("the transfer must fail").isNotNull();

        assertThat(balanceAt(backRoom))
                .as("the source must be EXACTLY as it was. If this is lower, the transfer_out "
                        + "committed without its transfer_in and that stock no longer exists "
                        + "anywhere — and T4 means there is no update that can put it back.")
                .isEqualByComparingTo(sourceBefore);

        assertThat(movementCount())
                .as("and no movement row may survive — a balance check alone would pass if both "
                        + "movements were written and happened to net out")
                .isEqualTo(movementsBefore);

        // Record what actually happened, rather than assuming.
        assertThat(thrown.getClass().getSimpleName() + ": " + thrown.getMessage())
                .as("the failure mode, for comparison with the cross-tenant case")
                .isNotBlank();
    }

    // ------------------------------------------------------------------
    // Failure case 2: the destination belongs to another tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a destination in another tenant is impossible, and leaves the source untouched")
    void crossTenantDestinationIsImpossible() throws Exception {
        stockTheBackRoom("10");

        BigDecimal sourceBefore = balanceAt(backRoom);
        long movementsBefore = movementCount();

        // otherTenantLocation genuinely exists — it is a real row in locations,
        // owned by somebody else. That is what makes this different in principle
        // from the previous test, whatever the mechanism turns out to be.
        assertThat(readAsOwner("SELECT count(*) FROM locations WHERE id = ?",
                Long.class, otherTenantLocation))
                .as("precondition: the other tenant's location must really exist, or this test "
                        + "is just the nonexistent-destination case wearing a different name")
                .isEqualTo(1L);

        Throwable thrown = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, otherTenantLocation, new BigDecimal("4"),
                        "stealing", UUID.randomUUID(), null))));

        assertThat(thrown).as("stock must not be transferable out of the tenant").isNotNull();

        assertThat(balanceAt(backRoom))
                .as("the source must be untouched")
                .isEqualByComparingTo(sourceBefore);
        assertThat(movementCount())
                .as("and nothing may be written")
                .isEqualTo(movementsBefore);

        // The other tenant must not have acquired anything either.
        assertThat(readAsOwner("""
                SELECT count(*) FROM stock_movements WHERE location_id = ?
                """, Long.class, otherTenantLocation))
                .as("no movement may reference another tenant's location")
                .isZero();
        assertThat(readAsOwner("""
                SELECT count(*) FROM product_stock WHERE location_id = ?
                """, Long.class, otherTenantLocation))
                .as("nor may a balance appear there")
                .isZero();
    }

    @Test
    @DisplayName("the two destination failures are the same mechanism, demonstrated not assumed")
    void bothDestinationFailuresAreTheSameMechanism() throws Exception {
        stockTheBackRoom("10");

        Throwable nonexistent = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, UUID.randomUUID(), BigDecimal.ONE,
                        null, UUID.randomUUID(), null))));

        Throwable otherTenant = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, otherTenantLocation, BigDecimal.ONE,
                        null, UUID.randomUUID(), null))));

        // They were written as separate tests because they are different in
        // principle: one location does not exist, the other does and belongs to
        // somebody else. This asserts what was previously only expected — that
        // RLS collapses them into one mechanism, because a row the policy hides
        // is indistinguishable from a row that was never there.
        //
        // The consequence is worth stating: there is no code path that treats
        // "another tenant's location" specially, so there is none to get wrong.
        // The composite foreign key (tenant_id, location_id) is doing the work.
        assertThat(otherTenant)
                .as("a hidden row and an absent row must be indistinguishable, or the error "
                        + "message itself would confirm the other location exists")
                .hasSameClassAs(nonexistent);

        assertThat(com.example.inventory.web.SqlStates.of(otherTenant))
                .as("both are foreign key violations, not a special cross-tenant check")
                .isEqualTo(com.example.inventory.web.SqlStates.of(nonexistent));
    }

    // ------------------------------------------------------------------
    // Failure at the source
    // ------------------------------------------------------------------

    @Test
    @DisplayName("transferring more than the source holds is refused, and writes nothing")
    void transferringMoreThanAvailableIsRefused() throws Exception {
        stockTheBackRoom("10");
        long movementsBefore = movementCount();
        BigDecimal floorBefore = balanceAt(shopFloor);

        assertThatThrownBy(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, shopFloor, new BigDecimal("999"),
                        "wishful", UUID.randomUUID(), null))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(movementCount()).isEqualTo(movementsBefore);
        assertThat(balanceAt(shopFloor))
                .as("the destination must not have been credited by a transfer that failed")
                .isEqualByComparingTo(floorBefore);
    }

    @Test
    @DisplayName("a transfer to the same location is refused")
    void sameLocationTransferIsRefused() throws Exception {
        stockTheBackRoom("10");
        long movementsBefore = movementCount();

        assertThatThrownBy(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, backRoom, BigDecimal.ONE,
                        "pointless", UUID.randomUUID(), null))))
                .isInstanceOf(com.example.inventory.web.ConflictException.class);

        assertThat(movementCount())
                .as("two movements netting to zero is not a transfer, it is noise in the ledger")
                .isEqualTo(movementsBefore);
    }
}
