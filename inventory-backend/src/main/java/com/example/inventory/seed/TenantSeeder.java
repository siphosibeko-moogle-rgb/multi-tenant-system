package com.example.inventory.seed;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.auth.TenantRegistrationService;
import com.example.inventory.catalog.CatalogDtos.CategoryWriteRequest;
import com.example.inventory.catalog.CatalogDtos.LocationWriteRequest;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.catalog.ProductCatalog;
import com.example.inventory.inventory.InsufficientStockException;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.purchasing.GoodsReceiptService;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderLineRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderWriteRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptLine;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptRequest;
import com.example.inventory.purchasing.PurchaseOrderService;
import com.example.inventory.sales.SaleService;
import com.example.inventory.sales.SalesDtos.SaleLineRequest;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.tenancy.TenantContext;

/**
 * M6: builds 6-12 months of realistic history for one tenant by calling the
 * same services a real request would — {@code SaleService}, the ledger's
 * {@code adjustment} path, {@code PurchaseOrderService},
 * {@code GoodsReceiptService} — never a raw {@code INSERT} into a
 * transactional table. See each {@code seedXxx} method for why a given shape
 * is produced the way it is; the reasoning is what M7 will actually depend
 * on, not just the resulting row counts.
 *
 * <p>Plain {@code @Component}, not profile-gated — {@code SeedDataRunner} is
 * the {@code --spring.profiles.active=seed} entry point that calls this for
 * two real tenants; this class is what {@code SeedDataVerificationTest}
 * exercises directly, at a shorter window, so the mechanism is proven by a
 * fast test rather than only by a slow manual run.
 *
 * <h2>Two deliberate exceptions to "no raw SQL"</h2>
 *
 * <ol>
 * <li>Suppliers. M5 built {@code GET /suppliers/{supplierId}} only —
 * deliberately, see {@code SupplierService}'s Javadoc — so there is no real
 * mechanism to call. One {@code INSERT} per supplier, flagged here and in
 * {@link #createSupplier}.
 * <li>A purchase order's {@code ordered_at}. {@code PurchaseOrderService
 * .submit} always stamps it to the real current instant — deliberately, so a
 * client cannot lie about when it ordered (M5) — and the contract gives a
 * seed script no other way to place a PO's history at a historical date. The
 * PO itself still goes through {@code create}/{@code submit} for real: this
 * patches one timestamp afterward, on a row the real service created,
 * exercising the real guard and lock. See {@link #seedSupplierHistory}.
 * </ol>
 */
@Component
public class TenantSeeder {

    private final TenantRegistrationService registration;
    private final ProductCatalog catalog;
    private final SaleService sales;
    private final StockLedgerService ledger;
    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService goodsReceipts;
    private final JdbcTemplate jdbc;

    public TenantSeeder(TenantRegistrationService registration,
                        ProductCatalog catalog,
                        SaleService sales,
                        StockLedgerService ledger,
                        PurchaseOrderService purchaseOrders,
                        GoodsReceiptService goodsReceipts,
                        @Qualifier("appDataSource") DataSource appDataSource) {
        this.registration = registration;
        this.catalog = catalog;
        this.sales = sales;
        this.ledger = ledger;
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts = goodsReceipts;
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** Everything a caller needs to find and verify what was seeded. */
    public record SeededTenant(
            UUID tenantId,
            UUID ownerId,
            UUID locationId,
            UUID supplierId,
            Map<String, UUID> productIdsByShape) {
    }

    /**
     * @param slug        must be a valid tenant slug (lowercase, digits,
     *                    hyphens) and unique across the database — callers
     *                    seeding more than once per run must vary it
     * @param randomSeed  makes the day-to-day randomness reproducible for a
     *                    given slug, without making every tenant identical
     * @param windowWeeks the length of the demand history. 30 (~7 months) in
     *                    real seed runs, comfortably inside the 6-12 month
     *                    ask; shorter in tests so the mechanism can be
     *                    proven quickly. Must stay above ~14 weeks for the
     *                    intermittent shape to mean anything (ADR §5).
     */
    public SeededTenant seedTenant(String businessName, String slug, long randomSeed, int windowWeeks) {
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusWeeks(windowWeeks);
        Random rng = new Random(randomSeed);

        AuthTokens tokens = registration.register(new TenantRegistrationRequest(
                businessName, slug, "owner+" + slug + "@example.test", "SeedPassw0rd!1",
                "Seed Owner", null, null));
        UUID tenantId = tokens.user().tenant().id();
        UUID ownerId = tokens.user().id();

        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, ownerId, "owner"));
        try {
            UUID locationId = catalog.createLocation(
                    new LocationWriteRequest("Main Store", null, true)).id();
            UUID categoryId = catalog.createCategory(
                    new CategoryWriteRequest("Bakery", null)).id();

            UUID steadyId = createProduct(slug + "-steady", "Steady Seller Bread", categoryId);
            UUID intermittentId = createProduct(slug + "-inter", "Intermittent Spice Mix", categoryId);
            UUID stockoutId = createProduct(slug + "-stockout", "Popular Croissant", categoryId);
            UUID trendingId = createProduct(slug + "-trend", "Trending Cold Brew", categoryId);
            UUID deadId = createProduct(slug + "-dead", "Discontinued Fruitcake", categoryId);
            UUID seasonalId = createProduct(slug + "-seasonal", "Weekend Party Platter", categoryId);
            UUID newId = createProduct(slug + "-new", "Brand New Sourdough", categoryId);

            UUID supplierId = createSupplier(tenantId, "Golden Wheat Supplies " + slug, 7);

            seedSteadySeller(steadyId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedIntermittentSeller(intermittentId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedStockoutProduct(stockoutId, supplierId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedTrendingProduct(trendingId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedDeadStock(deadId, locationId, windowStart);
            seedSeasonalProduct(seasonalId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedBrandNew(newId, locationId, today, new Random(rng.nextLong()));

            // "At least one purchase order per major product": the stockout
            // product's own restock above already went through a real PO
            // (the only sensible place for it — any date this method chose
            // independently risks landing inside that product's own
            // scripted empty-shelf window). Steady, intermittent and
            // trending get their PO history here, since none of them have a
            // narrative-sensitive stock timing to collide with.
            seedSupplierHistory(supplierId, locationId, List.of(steadyId, intermittentId, trendingId),
                    windowStart, today, new Random(rng.nextLong()));

            Map<String, UUID> shapes = new LinkedHashMap<>();
            shapes.put("steady", steadyId);
            shapes.put("intermittent", intermittentId);
            shapes.put("stockout", stockoutId);
            shapes.put("trending", trendingId);
            shapes.put("dead", deadId);
            shapes.put("seasonal", seasonalId);
            shapes.put("new", newId);

            return new SeededTenant(tenantId, ownerId, locationId, supplierId, shapes);
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // Product shapes
    // ------------------------------------------------------------------

    /**
     * The ADR's worked example (§6: "You sell about N a week..."), at low
     * variance. Sells on ~90% of days at a narrow 2-4 unit range,
     * which is deliberately NOT 100%: a generator that sells every single
     * day never produces a zero-demand day, and "demand_daily includes
     * zero-demand days" is M6's own most-common-bug warning. The 10% of
     * silent days are exactly that gap.
     *
     * <p><strong>The rate this actually produces is ~2.65/day (~18.6/week)</strong>,
     * measured by M7 step 1's rollup and reconciled against the ledger — 562
     * units over 212 days. Earlier prose called this shape "20/week"; that
     * number was written before anything measured it. Do not retune the
     * numbers below to chase it. See {@code docs/adr/forecasting.md} §8 item 1.
     *
     * <h2>Why this restocks itself, reactively, uncapped</h2>
     *
     * <p>A real seed run found this the hard way, twice. First here: a
     * single 200-unit opening stock covers roughly 10 weeks of this rate
     * (worst case ~90% x 4 x 70 = 252, so even a short test window was
     * already close), nowhere near a 30-week window's ~90% x ~3 x 210 ≈ 567
     * units of demand. Fixed once with a hand-computed periodic top-up sized
     * against THIS shape's own worst case — and the same real run then found
     * the identical class of bug in {@link #seedTrendingProduct}, whose
     * worst case is a different number because its rate grows. Hand-tuning
     * a margin per shape is exactly what turned out not to be reliable, so
     * {@link #restockIfLow} replaces it here and everywhere else in this
     * class that sells continuously: it reacts to the actual balance
     * instead of a calculation about what the balance should be, which
     * makes it correct for any demand curve — flat, growing, whatever comes
     * next — without re-deriving a margin for each one.
     *
     * <p>Deliberately NOT switched to {@link #sellUpTo} — capping demand to
     * whatever happens to be on the shelf is right for the stockout shape,
     * where running dry IS the point, but wrong here: silently suppressing
     * sales near a shortfall would make the "roughly consistent, low
     * variance" shape quietly taper off late in the window, which is a
     * second, unintended demand shape hiding inside the one this method
     * promises. A steady seller that is genuinely popular gets restocked
     * often in real life; this generator does the same.
     */
    void seedSteadySeller(UUID productId, UUID locationId, LocalDate start, LocalDate endExclusive, Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(200), new BigDecimal("8.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("8.00"));
            if (rng.nextDouble() < 0.90) {
                sell(productId, locationId, date, 2 + rng.nextInt(3));
            }
        }
    }

    /**
     * Sells on roughly 1 day in 10 — deliberately breaks a naive moving
     * average and is what routes {@code MethodSelector} to Croston once M7
     * exists. The window this is called with must be long enough that ~10%
     * of its days clears the ADR's 10-non-zero-day floor with room to
     * spare — {@code seedTenant} enforces that via {@code windowWeeks}, not
     * this method, since readiness is a property of the whole window, not
     * of a single product's generator.
     */
    void seedIntermittentSeller(UUID productId, UUID locationId, LocalDate start, LocalDate endExclusive,
                                Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(60), new BigDecimal("12.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            // Comfortably above what a 1-in-10 rate needs (§ arithmetic in
            // seedSteadySeller's Javadoc) — kept anyway, for the same reason
            // every other continuously-selling shape has it: a margin that
            // is merely "probably enough" is exactly what failed twice.
            restockIfLow(productId, locationId, date, new BigDecimal("12.00"));
            if (rng.nextDouble() < 0.10) {
                sell(productId, locationId, date, 1 + rng.nextInt(3));
            }
        }
    }

    /**
     * Demand that shifts over the window — a real trend, not a flat rate
     * with noise. Weekly target quantity rises linearly from ~5/week at the
     * start to ~5 + 0.6*weeks by the end (roughly 22/week at 30 weeks), so a
     * method that assumes a stationary mean (plain moving average) will
     * visibly lag it. Restocks via {@link #restockIfLow} for the same
     * reason {@link #seedSteadySeller} does — see that method's Javadoc:
     * this is the shape whose GROWING rate is what actually found the bug a
     * flat-rate margin, calculated by hand, did not generalize to.
     */
    void seedTrendingProduct(UUID productId, UUID locationId, LocalDate start, LocalDate endExclusive, Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(250), new BigDecimal("6.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("6.00"));
            double weekIndex = ChronoUnit.DAYS.between(start, date) / 7.0;
            double weeklyTarget = 5.0 + weekIndex * 0.6;
            double dailyProbability = Math.min(0.85, weeklyTarget / 7.0 / 2.0);
            if (rng.nextDouble() < dailyProbability) {
                int qty = Math.max(1, (int) Math.round(weeklyTarget / 7.0 * (0.7 + rng.nextDouble() * 0.6)));
                sell(productId, locationId, date, qty);
            }
        }
    }

    /** No sales at all, ever, in this window — dead stock, exactly as advertised. */
    void seedDeadStock(UUID productId, UUID locationId, LocalDate openingDate) {
        stockUp(productId, locationId, openingDate, new BigDecimal(15), new BigDecimal("20.00"));
    }

    /**
     * Under 2 weeks of SALES history, deliberately below the ADR's 42-day
     * readiness floor on both dimensions at once. The product row itself is
     * created at the same time as every other product in this tenant (see
     * {@code seedTenant}) rather than backdated or postdated — nothing
     * currently reads {@code products.created_at} for readiness, and once
     * M7's rollup exists it will key off {@code demand_daily}'s own row
     * span, which is exactly what restricting the SALES to the last 10 days
     * produces.
     */
    /**
     * A genuine weekly cycle: quiet on weekdays, busy at the weekend.
     *
     * <p>Added in M7 step 2 for a specific reason. M7 gates reorder points on
     * {@code DemandSeries.seasonalityIndicator()} — a seasonal product's peaks
     * and troughs get averaged flat by every method the selector can choose, so
     * its reorder point carries an explicit caveat. Until this shape existed,
     * that detection could only be proven against a hand-constructed fixture:
     * none of the other six shapes is cyclical, and M6's "seasonal or trending"
     * shape is implemented purely as a ramp. A gate nothing real exercises is a
     * gate nobody finds out is broken.
     *
     * <p>Weekly rather than annual because a 30-week window contains 30 weekly
     * cycles and less than one annual one — a yearly shape could not be detected
     * at this window length no matter how correct the detector.
     *
     * <p>Deliberately kept <em>flat</em> in trend and <em>frequent</em> in
     * selling days, so it routes to {@code moving_average} and the caveat is the
     * only thing distinguishing it. A shape that also trended would leave it
     * ambiguous which mechanism the test was exercising.
     */
    void seedSeasonalProduct(UUID productId, UUID locationId, LocalDate start,
                             LocalDate endExclusive, Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(120), new BigDecimal("5.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("5.00"));

            DayOfWeek dayOfWeek = date.getDayOfWeek();
            boolean weekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;

            // The amplitude is what makes the cycle detectable: a weekend sells
            // roughly five times a weekday. A gentler cycle would be more
            // realistic and less useful — the point of a fixture is to make one
            // mechanism unambiguous, and a marginal signal would leave a failure
            // meaning either "the detector broke" or "the data was borderline".
            if (weekend) {
                sell(productId, locationId, date, 8 + rng.nextInt(3));
            } else if (rng.nextDouble() < 0.7) {
                sell(productId, locationId, date, 1 + rng.nextInt(2));
            }
        }
    }

    void seedBrandNew(UUID productId, UUID locationId, LocalDate today, Random rng) {
        LocalDate start = today.minusDays(10);
        stockUp(productId, locationId, start, new BigDecimal(30), new BigDecimal("9.00"));
        for (LocalDate date = start; date.isBefore(today); date = date.plusDays(1)) {
            if (rng.nextDouble() < 0.4) {
                sell(productId, locationId, date, 1 + rng.nextInt(2));
            }
        }
    }

    /**
     * A genuine stockout: sold down to zero by real sales (never set
     * directly), left at zero for several days during which a real sale
     * attempt is made and confirmed refused — not skipped — then restocked
     * and resumed. This is what {@code had_stockout} exists to flag once
     * M7's rollup exists: the censored days below are not "no demand", they
     * are "demand nobody could fill", and a generator that only produces a
     * zero stock LEVEL without an actual refused sale cannot be told apart
     * from ordinary dead stock.
     */
    void seedStockoutProduct(UUID productId, UUID supplierId, UUID locationId, LocalDate start,
                             LocalDate endExclusive, Random rng) {
        long totalDays = ChronoUnit.DAYS.between(start, endExclusive);
        LocalDate stockoutStart = start.plusDays(totalDays * 4 / 10); // roughly 40% into the window

        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(40), new BigDecimal("7.00"));

        // Baseline selling, drawing the shelf down toward zero. Capped to
        // current stock rather than a fixed opening amount: this is what
        // makes the shape correct at any window length, not just the one
        // this generator happens to have been tuned against.
        LocalDate date = start;
        while (date.isBefore(stockoutStart)) {
            if (rng.nextDouble() < 0.6) {
                sellUpTo(productId, locationId, date, 2 + rng.nextInt(3));
            }
            date = date.plusDays(1);
        }

        // Force the last of the shelf out exactly on stockoutStart, so the
        // refused attempts below are refused from day one of the window,
        // not after a few more days of coincidental depletion.
        BigDecimal remaining = currentStock(productId, locationId);
        if (remaining.signum() > 0) {
            sell(productId, locationId, stockoutStart, remaining.intValue());
        }

        // The stockout itself: real refused sales on real days, not an
        // absence of activity. If any of these unexpectedly succeeds, stock
        // was left on the shelf and this is not a genuine stockout — fail
        // loudly rather than seed a shape that looks right and isn't.
        int stockoutDays = 5;
        for (int i = 0; i < stockoutDays; i++) {
            LocalDate refusedDate = stockoutStart.plusDays(i);
            boolean refused = attemptSaleExpectingRefusal(productId, locationId, refusedDate, 2);
            if (!refused) {
                throw new IllegalStateException(
                        "Seed data bug: a sale succeeded during the intended stockout window for "
                                + productId + " on " + refusedDate + " — the shelf was not actually empty.");
            }
        }

        // Restock through a REAL purchase order — the natural, realistic
        // trigger for a restock is exactly "we just ran out" — and resume a
        // lighter baseline for the rest of the window so there is a genuine
        // "before and after" to compare the outage against. This is this
        // product's contribution to "at least one PO per major product":
        // the only sensible place for it, since any date this method chose
        // independently for a SEPARATE PO would risk landing inside its own
        // scripted empty-shelf window above.
        //
        // Once resumed, this behaves like any other continuously-selling
        // shape — restockIfLow, not a one-off top-up. A single extra top-up
        // partway through was tried first and was not enough to survive a
        // full-length real run's post-restock tail (same lesson as
        // seedSteadySeller's Javadoc: a margin calculated once for "this
        // much window" does not generalize to a different window length).
        LocalDate restockDate = stockoutStart.plusDays(stockoutDays);
        orderAndReceive(supplierId, locationId, productId, new BigDecimal(35), new BigDecimal("7.00"),
                restockDate.minusDays(4), 4);
        for (LocalDate d = restockDate.plusDays(1); d.isBefore(endExclusive); d = d.plusDays(1)) {
            restockIfLow(productId, locationId, d, new BigDecimal("7.00"));
            if (rng.nextDouble() < 0.5) {
                sell(productId, locationId, d, 2 + rng.nextInt(2));
            }
        }
    }

    // ------------------------------------------------------------------
    // Purchasing / suppliers
    // ------------------------------------------------------------------

    /**
     * One supplier used by several products' worth of restocking, receiving
     * (with {@link #seedStockoutProduct}'s own restock) well above the ADR's
     * n>=5 trust threshold (§2), so {@code observedLeadTime} is populated
     * with a real sample size once queried, not just a lone row.
     */
    void seedSupplierHistory(UUID supplierId, UUID locationId, List<UUID> productIds,
                             LocalDate windowStart, LocalDate today, Random rng) {
        int receiptCount = 6;
        long totalDays = ChronoUnit.DAYS.between(windowStart, today);

        for (int i = 0; i < receiptCount; i++) {
            UUID productId = productIds.get(i % productIds.size());
            LocalDate orderedDate = windowStart.plusDays(totalDays * i / receiptCount);
            int leadDays = 3 + rng.nextInt(6); // 3..8 — real variance, not a constant
            BigDecimal quantity = new BigDecimal(40 + rng.nextInt(20));
            orderAndReceive(supplierId, locationId, productId, quantity, new BigDecimal("8.00"),
                    orderedDate, leadDays);
        }
    }

    /**
     * One full order → receive cycle for one product, through the real
     * services (T5-equivalent for purchasing: {@code PurchaseOrderService}
     * and {@code GoodsReceiptService} are the only classes that touch
     * {@code purchase_orders}/{@code purchase_order_items}). {@code
     * orderedDate} places the order historically by patching {@code
     * ordered_at} after the real {@code submit()} call — see the class
     * Javadoc for why that one column is the exception.
     */
    private void orderAndReceive(UUID supplierId, UUID locationId, UUID productId, BigDecimal quantity,
                                 BigDecimal unitCost, LocalDate orderedDate, int leadDays) {
        OffsetDateTime orderedAt = orderedDate.atTime(9, 0).atOffset(ZoneOffset.UTC);
        OffsetDateTime receivedAt = orderedAt.plusDays(leadDays);

        var created = purchaseOrders.create(new PurchaseOrderWriteRequest(
                supplierId, locationId, null, "Seed restock", null,
                List.of(new PurchaseOrderLineRequest(productId, quantity, unitCost))));
        UUID poId = created.id();

        purchaseOrders.submit(poId);
        jdbc.update("UPDATE purchase_orders SET ordered_at = ? WHERE id = ?",
                java.sql.Timestamp.from(orderedAt.toInstant()), poId);

        goodsReceipts.receive(poId, new ReceiptRequest(receivedAt,
                List.of(new ReceiptLine(productId, quantity, unitCost))));
    }

    /**
     * The one deliberate raw {@code INSERT} in this generator — see the
     * class Javadoc. {@code lead_time_days} is set well away from the real
     * observed figures {@link #seedSupplierHistory} produces (3-8 days), so
     * a bug that returned the promised value instead of the observed one
     * would be visible rather than accidentally plausible.
     */
    private UUID createSupplier(UUID tenantId, String name, int promisedLeadTimeDays) {
        UUID supplierId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO suppliers (id, tenant_id, name, lead_time_days)
                VALUES (?, ?, ?, ?)
                """, supplierId, tenantId, name, promisedLeadTimeDays);
        return supplierId;
    }

    // ------------------------------------------------------------------
    // Shared mechanics
    // ------------------------------------------------------------------

    private UUID createProduct(String sku, String name, UUID categoryId) {
        catalog.create(new ProductWriteRequest(
                sku, null, name, null, categoryId, "each",
                new BigDecimal("8.00"), new BigDecimal("15.00"), new BigDecimal("0.15"),
                null, null, null, true, false, null));
        // ProductCatalog.create returns Versioned<Product>, which is
        // package-private to catalog — inaccessible from here by design (the
        // version travels beside the DTO, never in it). Reading the id back
        // by the sku this method just chose is simpler than the alternative
        // of moving this class into that package for one field.
        return jdbc.queryForObject("SELECT id FROM products WHERE sku = ?", UUID.class, sku);
    }

    private void sell(UUID productId, UUID locationId, LocalDate date, int quantity) {
        sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(productId, new BigDecimal(quantity), null, null)),
                null, locationId, null, null, null, null,
                date.atTime(12, 0).atOffset(ZoneOffset.UTC)));
    }

    /**
     * Sells up to {@code wanted}, capped to whatever is actually on hand —
     * for shapes that deliberately run a product close to empty, where a
     * fixed quantity would eventually oversell and abort the whole seed run
     * (T12: the ledger trigger refuses it, correctly, and a seed script has
     * no business papering over that with a pre-check either — this reads
     * the balance to decide how much to ASK for, same as a cashier would,
     * not to bypass the trigger's own refusal).
     */
    private void sellUpTo(UUID productId, UUID locationId, LocalDate date, int wanted) {
        int available = currentStock(productId, locationId).intValue();
        int actual = Math.min(wanted, available);
        if (actual > 0) {
            sell(productId, locationId, date, actual);
        }
    }

    /** @return true if the sale was refused for insufficient stock, as intended */
    private boolean attemptSaleExpectingRefusal(UUID productId, UUID locationId, LocalDate date, int quantity) {
        try {
            sell(productId, locationId, date, quantity);
            return false;
        } catch (InsufficientStockException e) {
            return true;
        }
    }

    private void stockUp(UUID productId, UUID locationId, LocalDate date, BigDecimal quantity,
                         BigDecimal unitCost) {
        ledger.post(new MovementRequest(productId, locationId, "adjustment", quantity, unitCost,
                null, null, "seed opening stock", date.atTime(8, 0).atOffset(ZoneOffset.UTC)));
    }

    /**
     * Tops up whenever the balance drops below a low-water mark, rather than
     * on a schedule computed in advance. Every shape that sells continuously
     * across the whole window (steady, intermittent, trending) calls this
     * once per day, before that day's sale attempt — see
     * {@link #seedSteadySeller}'s Javadoc for why a calculated margin turned
     * out not to generalize across shapes with different demand curves, and
     * why this replaced it everywhere rather than just where it first broke.
     *
     * <p>The threshold (30) and top-up (150) are sized against the worst
     * plausible SINGLE day's demand across every shape that calls this
     * (never more than ~5 units) — comfortably clears any one day, and the
     * next check the following day catches anything this one didn't.
     */
    private void restockIfLow(UUID productId, UUID locationId, LocalDate date, BigDecimal unitCost) {
        if (currentStock(productId, locationId).compareTo(new BigDecimal(30)) < 0) {
            stockUp(productId, locationId, date, new BigDecimal(150), unitCost);
        }
    }

    private BigDecimal currentStock(UUID productId, UUID locationId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);
    }
}
