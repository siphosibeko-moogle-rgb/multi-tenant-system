package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Twenty concurrent sales of a ten-unit product must leave exactly ten sold, ten
 * rejected, and a balance of zero — never negative.
 *
 * <h2>Why this test is written with real threads</h2>
 *
 * <p>Because the bug it is looking for only exists between threads. The
 * interesting failure is a read-then-write race: a service that asks "is there
 * enough?" and then inserts is correct in every single-threaded test ever
 * written, and wrong the moment two callers read the same balance before either
 * writes. Simulating concurrency — calling the method twenty times in a loop, or
 * mocking the pool — reproduces the sequential case twenty times and proves
 * nothing about the case that matters.
 *
 * <p>So: twenty platform threads, a real Hikari pool, a real PostgreSQL, and a
 * {@link CountDownLatch} so they arrive together rather than politely one after
 * another.
 *
 * <h2>What is actually under test</h2>
 *
 * <p>Not the trigger. {@code apply_stock_movement} does the enforcing, under the
 * row lock its own upsert takes, and PostgreSQL can be trusted to hold a lock.
 * What is under test is whether the <em>service layer</em> defeats it — by
 * pre-checking the balance in Java, by catching the refusal and retrying until it
 * "succeeds", or by swallowing the exception and reporting success. Each of those
 * passes a single-threaded oversell test and fails here.
 *
 * <h2>Demonstrated, not assumed</h2>
 *
 * <p>Making {@code StockLedgerService} swallow the refusal and report success —
 * the classic "the service layer defeats the trigger" bug — turns two of these
 * red: the sold/rejected counts become 20/0, and the single-threaded case stops
 * throwing. Verified on PG 16.14.
 *
 * <p>Worth knowing what a mutation <em>does not</em> break, because it says
 * where the guarantee actually lives: adding a Java-side
 * {@code SELECT quantity_on_hand} pre-check before the insert leaves this test
 * green. All twenty threads read "10 available" before any of them commits, all
 * twenty pass the pre-check, and the trigger still refuses exactly ten. The
 * pre-check is useless rather than harmful — which is the point. The lock is
 * doing the work, so the service must not be written as though the pre-check
 * were the defence.
 *
 * <h2>Each thread binds its own tenant</h2>
 *
 * <p>{@link TenantContext} is a plain {@code ThreadLocal}, deliberately not
 * inheritable, so a worker thread inherits nothing from the thread that submitted
 * the task. That is the intended design — an inheritable context is how one
 * request's tenant ends up on an unrelated thread — and it means each worker
 * below binds the tenant itself, exactly as a request thread would.
 */
@DisplayName("Concurrent oversell")
class ConcurrentOversellTest extends AbstractIntegrationTest {

    private static final int UNITS_IN_STOCK = 10;
    private static final int CONCURRENT_SALES = 20;

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    @Qualifier("appDataSource")
    private DataSource appDataSource;

    private static UUID tenantId;
    private static UUID productId;
    private static UUID locationId;
    private static boolean seeded;

    /**
     * A tenant with one location and one product, and nothing else.
     *
     * <p>Seeded over the owner connection (the container superuser, so RLS does
     * not apply) — right for a fixture, and never used for an assertion.
     * {@code allow_negative_stock} is left at its default of false, which is the
     * whole point: the trigger is permitted to refuse.
     */
    @BeforeEach
    void seedProduct() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        productId = UUID.randomUUID();
        locationId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'oversell-%s', 'Oversell Ltd')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-OVERSELL', 'Last Ten Units', false)
                    """.formatted(productId, tenantId));
        }
        seeded = true;
    }

    /**
     * Runs {@code work} with the tenant bound on the calling thread.
     *
     * <p>Uses the ordinary {@link TenantContext#bind}, not
     * {@code bindForRegistration} — that method is the single documented
     * exception to T1 and CLAUDE.md §12 states that a grep for its callers must
     * return exactly one, in {@code TenantRegistrationService}. A test helper is
     * not a good enough reason to make that two.
     *
     * <p>The user id is null, so movements are written with {@code created_by}
     * null rather than pointing at a user row that does not exist. That is what
     * the column's nullability is for.
     */
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
    @DisplayName("20 concurrent sales of a 10-unit product: 10 sold, 10 rejected, never negative")
    void twentyConcurrentSalesOfTenUnits() throws Exception {
        // Stock it, through the ledger rather than by hand — the receipt is a
        // movement like any other, and using SQL here would leave the cache and
        // the ledger agreeing only by luck.
        asTenant(() -> ledger.post(new MovementRequest(
                productId, locationId, "purchase_receipt",
                new BigDecimal(UNITS_IN_STOCK), new BigDecimal("10.00"),
                null, null, "opening", null)));

        assertThat(readAsOwner("SELECT quantity_on_hand FROM product_stock WHERE product_id = ?",
                BigDecimal.class, productId))
                .as("precondition: exactly %d units, or the arithmetic below means nothing",
                        UNITS_IN_STOCK)
                .isEqualByComparingTo(new BigDecimal(UNITS_IN_STOCK));

        AtomicInteger sold = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        // Every thread waits on this, so they contend rather than queue.
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(CONCURRENT_SALES);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SALES);
        try {
            for (int i = 0; i < CONCURRENT_SALES; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        asTenant(() -> ledger.post(new MovementRequest(
                                productId, locationId, "sale",
                                BigDecimal.ONE.negate(), null,
                                "sale", UUID.randomUUID(), null, null)));
                        sold.incrementAndGet();
                    } catch (InsufficientStockException expected) {
                        // The correct outcome for ten of these twenty.
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // Anything else — a deadlock, a serialisation failure, a
                        // pool timeout — is counted separately rather than being
                        // quietly lumped in with "rejected", which would let a
                        // broken run look like a passing one.
                        unexpected.incrementAndGet();
                    } finally {
                        allFinished.countDown();
                    }
                    return null;
                });
            }

            startTogether.countDown();
            assertThat(allFinished.await(60, TimeUnit.SECONDS))
                    .as("all %d attempts should finish; a timeout here means a deadlock",
                            CONCURRENT_SALES)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get())
                .as("no attempt should fail for a reason other than insufficient stock")
                .isZero();
        assertThat(sold.get())
                .as("exactly %d sales may succeed — one per unit that existed", UNITS_IN_STOCK)
                .isEqualTo(UNITS_IN_STOCK);
        assertThat(rejected.get())
                .as("and the other %d must be refused", CONCURRENT_SALES - UNITS_IN_STOCK)
                .isEqualTo(CONCURRENT_SALES - UNITS_IN_STOCK);

        BigDecimal finalBalance = readAsOwner(
                "SELECT quantity_on_hand FROM product_stock WHERE product_id = ?",
                BigDecimal.class, productId);

        assertThat(finalBalance)
                .as("the balance must be exactly zero — 10 in, 10 out")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalBalance.signum())
                .as("NEVER negative. A negative balance here is stock sold that did not exist.")
                .isNotNegative();

        // The ledger and its cache must agree. If they do not, one of the twenty
        // threads wrote a movement whose effect was lost, which is the same bug
        // wearing a different hat.
        BigDecimal rebuilt = readAsOwner("""
                SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements WHERE product_id = ?
                """, BigDecimal.class, productId);
        assertThat(rebuilt)
                .as("SUM(quantity_delta) must equal the cached balance")
                .isEqualByComparingTo(finalBalance);

        assertThat(readAsOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, productId))
                .as("one receipt plus exactly %d sales — a rejected sale must leave NO row",
                        UNITS_IN_STOCK)
                .isEqualTo(UNITS_IN_STOCK + 1L);
    }

    @Test
    @DisplayName("a rejected sale writes nothing at all")
    void aRejectedSaleIsNotPartiallyApplied() throws Exception {
        UUID otherProduct = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-EMPTY', 'Never Stocked', false)
                    """.formatted(otherProduct, tenantId));
        }

        // Selling from a product that has never been stocked: the trigger's
        // upsert creates the row at -1, sees it is negative, and raises.
        try {
            asTenant(() -> ledger.post(new MovementRequest(
                    otherProduct, locationId, "sale", BigDecimal.ONE.negate(),
                    null, null, null, null, null)));
            throw new AssertionError("expected the sale to be refused");
        } catch (InsufficientStockException expected) {
            assertThat(expected.productId()).isEqualTo(otherProduct);
            assertThat(expected.requested()).isEqualByComparingTo(BigDecimal.ONE);
        }

        assertThat(readAsOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, otherProduct))
                .as("the refused movement must not be in the ledger")
                .isZero();

        // The trigger's upsert ran before it raised, so the rollback is what
        // removes the product_stock row it created. Worth asserting: a
        // half-applied refusal would leave a phantom -1 balance behind.
        assertThat(readAsOwner("SELECT count(*) FROM product_stock WHERE product_id = ?",
                Long.class, otherProduct))
                .as("and must leave no cached balance behind either")
                .isZero();
    }

    @Test
    @DisplayName("a product that allows negative stock is not blocked")
    void allowNegativeStockIsHonoured() throws Exception {
        // The other side of the rule. If this failed, the trigger would be
        // refusing everything and the concurrency test above would pass for
        // entirely the wrong reason.
        UUID backorderable = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-BACKORDER', 'Backorderable', true)
                    """.formatted(backorderable, tenantId));
        }

        var posted = asTenant(() -> ledger.post(new MovementRequest(
                backorderable, locationId, "sale", BigDecimal.ONE.negate(),
                null, null, null, null, null)));

        assertThat(posted.balanceAfter())
                .as("allow_negative_stock means exactly that")
                .isEqualByComparingTo(new BigDecimal("-1"));
    }

    @Test
    @DisplayName("the ledger is append-only, even to the application role")
    void theLedgerCannotBeRewritten() {
        // T4, from the application's own connection rather than the owner's:
        // V2 revokes UPDATE and DELETE, so the attempt is refused before the
        // trigger is even reached.
        JdbcTemplate appJdbc = new JdbcTemplate(appDataSource);

        List<String> statements = List.of(
                "UPDATE stock_movements SET quantity_delta = 999 WHERE product_id = '%s'"
                        .formatted(productId),
                "DELETE FROM stock_movements WHERE product_id = '%s'".formatted(productId));

        for (String sql : statements) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> appJdbc.update(sql))
                    .as("the ledger must not be rewritable: %s", sql)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
