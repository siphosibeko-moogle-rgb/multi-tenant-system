package com.example.inventory.catalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.catalog.CatalogDtos.Category;
import com.example.inventory.catalog.CatalogDtos.CategoryWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Location;
import com.example.inventory.catalog.CatalogDtos.LocationWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.catalog.CatalogDtos.ProductPage;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;
import com.example.inventory.web.PreconditionFailedException;

/**
 * Products, categories and locations, within the caller's tenant.
 *
 * <h2>Uniqueness here is per tenant, not global</h2>
 *
 * <p>V1's indexes are {@code (tenant_id, upper(sku))} and
 * {@code (tenant_id, barcode)} — so two businesses can each stock "SKU-001", and
 * two businesses can each sell a can of beans with the same manufacturer
 * barcode. That is not a detail, it is the product working: barcodes are issued
 * by manufacturers and are shared by every shop that stocks the item.
 *
 * <p>A well-meaning "make SKUs unique" change to a global index would break the
 * second tenant to register a common product, and — this is the dangerous part —
 * it would <strong>not</strong> show up in the tenant isolation sweep, because
 * nothing would leak. Registration would simply start failing for real
 * customers. {@code CatalogIsolationTest} holds the same SKU and the same
 * barcode in two tenants at once for exactly that reason.
 *
 * <p>None of the queries below carries a {@code WHERE tenant_id = ?}. Scoping is
 * RLS's job (T2); the lookup by barcode returns the caller's product because the
 * policy filters the other tenant's row out, not because the SQL asked it to.
 */
@Service
public class ProductCatalog {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /**
     * <p>{@code reorder_point} is the EFFECTIVE one — the product's own if set,
     * otherwise the current forecast's — exactly as {@code v_stock_status}
     * computes it.
     *
     * <p>It used to be {@code p.reorder_point} alone, and that was wrong from
     * the moment M7 started writing forecast reorder points. The CASE that
     * derives {@code stockState} was faithfully mirrored between here and the
     * view, so the logic never diverged; the <em>input</em> did. The view
     * coalesced in the forecast's figure and this did not, so the same product
     * at the same instant came back {@code ok} from {@code GET /products} and
     * {@code reorder} from {@code GET /inventory}.
     *
     * <p>On screen that read as the Stock list marking a product fine while the
     * reorder list told the shop owner to order it — a contradiction between two
     * screens with no way for them to tell which was right. Found by building
     * both screens and looking at them, not by any test: {@code StockStateTest}
     * compares the two CASE expressions, which agreed all along.
     *
     * <p>The join must stay filtered to {@code is_current}: {@code forecasts}
     * keeps superseded rows for accuracy scoring, and joining them all would
     * multiply every product row by its forecast history.
     */
    private static final String PRODUCT_COLUMNS = """
            SELECT p.id, p.sku, p.barcode, p.name, p.description, p.category_id,
                   p.unit_of_measure, p.cost_price, p.selling_price, p.tax_rate,
                   COALESCE(p.reorder_point, MAX(f.reorder_point)) AS reorder_point,
                   p.is_tracked, p.is_active, p.allow_negative_stock,
                   p.updated_at, p.version,
                   COALESCE(SUM(ps.quantity_on_hand), 0)   AS quantity_on_hand,
                   COALESCE(SUM(ps.quantity_available), 0) AS quantity_available
            FROM products p
            LEFT JOIN product_stock ps ON ps.product_id = p.id
            LEFT JOIN forecasts f
                   ON f.tenant_id = p.tenant_id AND f.product_id = p.id AND f.is_current
            """;

    private static final String PRODUCT_GROUP_BY = " GROUP BY p.id ";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StockLedgerService ledger;

    public ProductCatalog(@Qualifier("appDataSource") DataSource appDataSource,
                          TransactionTemplate transactions,
                          StockLedgerService ledger) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.ledger = ledger;
    }

    // ------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------

    public ProductPage list(String cursor, Integer limit, String q, UUID categoryId,
                            Boolean active) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<Object> args = new ArrayList<>();

        StringBuilder sql = new StringBuilder(PRODUCT_COLUMNS).append(" WHERE p.deleted_at IS NULL");

        if (q != null && !q.isBlank()) {
            // Matches the three things a person actually searches by. ILIKE with a
            // leading wildcard cannot use the btree indexes; at catalogue sizes
            // that is fine, and M9's index review is where it stops being fine.
            sql.append(" AND (p.name ILIKE ? OR p.sku ILIKE ? OR p.barcode ILIKE ?)");
            String pattern = "%" + q.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = ?");
            args.add(categoryId);
        }
        if (active != null) {
            sql.append(" AND p.is_active = ?");
            args.add(active);
        }

        String decoded = decodeCursor(cursor);
        if (decoded != null) {
            sql.append(" AND (upper(p.sku), p.id::text) > (?, ?)");
            args.add(decoded.substring(0, decoded.lastIndexOf('|')).toUpperCase());
            args.add(decoded.substring(decoded.lastIndexOf('|') + 1));
        }

        sql.append(PRODUCT_GROUP_BY)
                .append(" ORDER BY upper(p.sku), p.id LIMIT ").append(pageSize + 1);

        List<Product> rows = jdbc.query(sql.toString(), ProductCatalog::mapProduct, args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<Product> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).sku() + "|" + page.get(page.size() - 1).id())
                : null;

        return new ProductPage(page, next);
    }

    public Optional<Versioned<Product>> find(UUID productId) {
        return jdbc.query(PRODUCT_COLUMNS + " WHERE p.id = ? AND p.deleted_at IS NULL"
                        + PRODUCT_GROUP_BY, ProductCatalog::mapVersioned, productId)
                .stream().findFirst();
    }

    /**
     * The scanner's hot path.
     *
     * <p>Returns the caller's product and no one else's — several tenants may
     * hold this exact barcode, and the {@code products} policy is what decides
     * which row this connection can see.
     */
    public Optional<Versioned<Product>> findByBarcode(String barcode) {
        return jdbc.query(PRODUCT_COLUMNS + " WHERE p.barcode = ? AND p.deleted_at IS NULL"
                        + PRODUCT_GROUP_BY, ProductCatalog::mapVersioned, barcode)
                .stream().findFirst();
    }

    public Versioned<Product> create(ProductWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();

        return transactions.execute(status -> {
            try {
                jdbc.update("""
                        INSERT INTO products
                            (id, tenant_id, sku, barcode, name, description, category_id,
                             unit_of_measure, cost_price, selling_price, tax_rate,
                             reorder_point, reorder_quantity, safety_stock,
                             is_tracked, allow_negative_stock)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                                COALESCE(?, 0), COALESCE(?, 0), COALESCE(?, 0),
                                ?, ?, ?, COALESCE(?, true), COALESCE(?, false))
                        """,
                        id, tenantId, request.sku(), blankToNull(request.barcode()), request.name(),
                        request.description(), request.categoryId(),
                        request.unitOfMeasureOrDefault(), request.costPrice(),
                        request.sellingPrice(), request.taxRate(), request.reorderPoint(),
                        request.reorderQuantity(), request.safetyStock(),
                        request.isTracked(), request.allowNegativeStock());
            } catch (DuplicateKeyException e) {
                throw conflictFor(e);
            }

            postOpeningStock(request, id);

            return find(id).orElseThrow(
                    () -> new IllegalStateException("product vanished after insert"));
        });
    }

    /**
     * Opening stock, posted as a movement rather than written to the cache.
     *
     * <p>Tempting to seed {@code product_stock} directly and save a round trip.
     * That would put a balance in the system with no ledger row explaining it,
     * so {@code SUM(quantity_delta)} would no longer reconcile with the cache —
     * and the reconciliation is the only reason to trust the cache at all.
     */
    private void postOpeningStock(ProductWriteRequest request, UUID productId) {
        var opening = request.openingStock();
        if (opening == null || opening.quantity() == null
                || opening.quantity().signum() <= 0) {
            return;
        }
        UUID locationId = opening.locationId() != null
                ? opening.locationId()
                : defaultLocationId().orElseThrow(() -> new ConflictException(
                        // Not 404. Nothing was looked up and not found: the
                        // request named no location and the business has none
                        // marked default, which is a state of the tenant rather
                        // than a missing resource. As a 404 it was also
                        // indistinguishable from "that product does not exist",
                        // so a client could not tell a fixable configuration
                        // problem from a bad id.
                        "This business has no default location; specify openingStock.locationId",
                        "no-default-location"));

        ledger.post(new MovementRequest(productId, locationId, "opening_balance",
                opening.quantity(), opening.unitCost(), null, null, "opening stock", null));
    }

    /**
     * Updates a product, refusing the write if it is based on a stale read.
     *
     * <p>The version check is the {@code updated_at} in the WHERE clause, which
     * makes this a compare-and-swap in the database rather than a check in Java.
     * That distinction is the whole point: reading the row, comparing timestamps
     * in Java and then updating would be a read-then-write race, and two
     * concurrent editors would both pass it. Here the second writer's UPDATE
     * matches zero rows, because PostgreSQL re-evaluates the WHERE against the
     * committed row after the first writer releases the lock.
     *
     * @param ifMatch the ETag from a prior GET, or null to accept last-write-wins
     */
    public Versioned<Product> update(UUID productId, ProductWriteRequest request,
                                     String ifMatch) {
        return transactions.execute(status -> {
            Long expectedVersion = ifMatch == null ? null : ETags.parse(ifMatch);

            StringBuilder sql = new StringBuilder("""
                    UPDATE products SET
                        sku = ?, barcode = ?, name = ?, description = ?, category_id = ?,
                        unit_of_measure = ?, cost_price = COALESCE(?, cost_price),
                        selling_price = COALESCE(?, selling_price),
                        tax_rate = COALESCE(?, tax_rate), reorder_point = ?,
                        reorder_quantity = ?, safety_stock = ?,
                        is_tracked = COALESCE(?, is_tracked),
                        allow_negative_stock = COALESCE(?, allow_negative_stock)
                    WHERE id = ? AND deleted_at IS NULL
                    """);
            List<Object> args = new ArrayList<>(List.of());
            args.add(request.sku());
            args.add(blankToNull(request.barcode()));
            args.add(request.name());
            args.add(request.description());
            args.add(request.categoryId());
            args.add(request.unitOfMeasureOrDefault());
            args.add(request.costPrice());
            args.add(request.sellingPrice());
            args.add(request.taxRate());
            args.add(request.reorderPoint());
            args.add(request.reorderQuantity());
            args.add(request.safetyStock());
            args.add(request.isTracked());
            args.add(request.allowNegativeStock());
            args.add(productId);

            if (expectedVersion != null) {
                // Compare-and-swap in the database. The version is NOT set here:
                // V4's trg_products_version increments it, so a writer cannot
                // forget and leave every future If-Match passing forever.
                sql.append(" AND version = ?");
                args.add(expectedVersion);
            }

            int updated;
            try {
                updated = jdbc.update(sql.toString(), args.toArray());
            } catch (DuplicateKeyException e) {
                throw conflictFor(e);
            }

            if (updated == 0) {
                // Zero rows means one of two things, and they are different
                // answers: the product is not there (404), or it is there and
                // somebody else has changed it since the caller read it (412).
                if (find(productId).isPresent()) {
                    throw new PreconditionFailedException(
                            "This product was modified by someone else; re-read it and retry");
                }
                throw new NotFoundException("No such product");
            }

            return find(productId).orElseThrow(() -> new NotFoundException("No such product"));
        });
    }

    /** Soft delete, so ledger rows referencing the product still resolve. */
    public boolean deactivate(UUID productId) {
        return jdbc.update("""
                UPDATE products SET is_active = false, deleted_at = now()
                WHERE id = ? AND deleted_at IS NULL
                """, productId) > 0;
    }

    // ------------------------------------------------------------------
    // Categories and locations
    // ------------------------------------------------------------------

    public List<Category> categories() {
        return jdbc.query("""
                SELECT c.id, c.name, c.parent_id,
                       (SELECT count(*) FROM products p
                         WHERE p.category_id = c.id AND p.deleted_at IS NULL) AS product_count
                FROM categories c ORDER BY c.name
                """, (rs, i) -> new Category(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("parent_id", UUID.class),
                        rs.getLong("product_count")));
    }

    public Category createCategory(CategoryWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO categories (id, tenant_id, name, parent_id) VALUES (?, ?, ?, ?)",
                    id, tenantId, request.name(), request.parentId());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A category with that name already exists",
                    "category-name-taken");
        }
        return new Category(id, request.name(), request.parentId(), 0);
    }

    public List<Location> locations() {
        return jdbc.query("SELECT id, name, address, is_default, is_active FROM locations "
                        + "ORDER BY is_default DESC, name",
                (rs, i) -> new Location(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getBoolean("is_default"),
                        rs.getBoolean("is_active")));
    }

    public Location createLocation(LocationWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());

        return transactions.execute(status -> {
            if (isDefault) {
                // locations_one_default is a partial unique index, so the old
                // default has to stand down before the new one can stand up.
                jdbc.update("UPDATE locations SET is_default = false WHERE is_default");
            }
            try {
                jdbc.update("""
                        INSERT INTO locations (id, tenant_id, name, address, is_default)
                        VALUES (?, ?, ?, ?, ?)
                        """, id, tenantId, request.name(), request.address(), isDefault);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("A location with that name already exists",
                        "location-name-taken");
            }
            return new Location(id, request.name(), request.address(), isDefault, true);
        });
    }

    public Optional<UUID> defaultLocationId() {
        return jdbc.query("SELECT id FROM locations WHERE is_default LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * Names which uniqueness rule was broken.
     *
     * <p>"Already exists" without saying what is a support ticket. Both indexes
     * are per tenant, so this discloses only what the caller's own colleague
     * already entered.
     */
    private static ConflictException conflictFor(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("products_tenant_barcode_uq")) {
            return new ConflictException(
                    "Another product in this business already uses that barcode",
                    "product-barcode-taken");
        }
        return new ConflictException(
                "Another product in this business already uses that SKU", "product-sku-taken");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Versioned<Product> mapVersioned(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new Versioned<>(mapProduct(rs, row), rs.getLong("version"));
    }

    private static Product mapProduct(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        BigDecimal onHand = rs.getBigDecimal("quantity_on_hand");
        BigDecimal available = rs.getBigDecimal("quantity_available");
        BigDecimal reorderPoint = rs.getBigDecimal("reorder_point");

        return new Product(
                rs.getObject("id", UUID.class),
                rs.getString("sku"),
                rs.getString("barcode"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("category_id", UUID.class),
                rs.getString("unit_of_measure"),
                rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("selling_price"),
                rs.getBigDecimal("tax_rate"),
                reorderPoint,
                onHand,
                available,
                stockState(onHand, available, reorderPoint),
                rs.getBoolean("is_tracked"),
                rs.getBoolean("is_active"),
                rs.getBoolean("allow_negative_stock"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    /**
     * Mirrors the CASE in {@code v_stock_status}, for the single-product reads.
     *
     * <p><strong>These two must agree.</strong> This method exists because the
     * per-product reads do not go through the view, so the same question is
     * answered in two places — and a divergence would show as the list and the
     * detail page disagreeing about the same product, which is the kind of bug
     * people report as "the app is wrong" without being able to say how.
     * {@code StockStateTest} asserts they agree across the interesting cases.
     *
     * <p>No reorder point means {@code ok}, not {@code unknown}: nothing sets a
     * reorder point until M7's forecaster, so {@code unknown} was the state of
     * essentially every product in every new business. Not knowing whether stock
     * is <em>low</em> is not the same as not knowing what the stock <em>is</em>.
     * See the note in {@code R__views.sql}, which is the fuller version.
     */
    private static String stockState(BigDecimal onHand, BigDecimal available,
                                     BigDecimal reorderPoint) {
        if (onHand == null || onHand.signum() <= 0) {
            return "out_of_stock";
        }
        if (reorderPoint == null) {
            return "ok";
        }
        return available != null && available.compareTo(reorderPoint) <= 0 ? "reorder" : "ok";
    }

    private static String encodeCursor(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return raw.contains("|") ? raw : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
