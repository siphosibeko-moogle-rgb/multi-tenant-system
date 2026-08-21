package com.example.inventory.purchasing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrder;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderDetail;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderLine;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderPage;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderWriteRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;

/**
 * Purchase orders: draft → ordered → (partial →) received, plus the read/list
 * side of the resource. Receiving stock against an order — the part that
 * writes to the ledger and to {@code supplier_lead_time_observations} — is
 * {@code GoodsReceiptService} (added in the next step), not this class, the same separation
 * {@code SaleService} and {@code StockLedgerService} already draw: this class
 * owns the commercial document, the receipt service owns what physically
 * happened when it arrived.
 *
 * <h2>T11 — no new tenant-scoped table</h2>
 *
 * <p>{@code purchase_orders}, {@code purchase_order_items} and
 * {@code supplier_lead_time_observations} were all created in {@code V1} and
 * have been in {@code TenantIsolationTest}, {@code SchemaSmokeTest} and
 * {@code LoginRoleTest}'s sweeps since M1 — this milestone adds no table, so
 * none of the three sweeps need a new entry.
 */
@Service
public class PurchaseOrderService {

    /** Matches the contract's {@code Limit} parameter: default 50, max 200. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PurchaseOrderService(@Qualifier("appDataSource") DataSource appDataSource,
                                TransactionTemplate transactions) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
    }

    /**
     * Creates a purchase order in {@code draft}. Nothing is ordered yet —
     * {@link #submit} is what stamps {@code orderedAt} and starts the
     * lead-time clock the ADR's reorder-point formula depends on.
     */
    public PurchaseOrderDetail create(PurchaseOrderWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID actor = TenantContext.currentUserId().orElse(null);

        return transactions.execute(status -> insertPurchaseOrder(tenantId, actor, request));
    }

    private PurchaseOrderDetail insertPurchaseOrder(UUID tenantId, UUID actor,
                                                     PurchaseOrderWriteRequest request) {
        // Existence checked with a read rather than left to the foreign key,
        // so a bad id is a clean 404 rather than a raw constraint violation —
        // the same reasoning as SaleService.readProduct. RLS makes another
        // tenant's supplier indistinguishable from a nonexistent one (T8).
        readSupplierName(request.supplierId());

        UUID locationId = request.locationId() != null
                ? request.locationId()
                : defaultLocationId().orElseThrow(() -> new ConflictException(
                        "This business has no default location; specify locationId",
                        "no-default-location"));

        UUID poId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, supplier_id, location_id, po_number, status,
                     expected_at, notes, created_by)
                VALUES (?, ?, ?, ?, ?, 'draft', ?, ?, ?)
                """,
                poId, tenantId, request.supplierId(), locationId, nextPoNumber(tenantId),
                request.expectedAt(), request.notes(), actor);

        BigDecimal total = BigDecimal.ZERO;
        for (var line : request.lines()) {
            BigDecimal unitCost = line.unitCost() != null ? line.unitCost() : readProductCost(line.productId());

            jdbc.update("""
                    INSERT INTO purchase_order_items
                        (tenant_id, purchase_order_id, product_id, quantity_ordered, unit_cost)
                    VALUES (?, ?, ?, ?, ?)
                    """, tenantId, poId, line.productId(), line.quantityOrdered(), unitCost);

            total = total.add(unitCost.multiply(line.quantityOrdered()).setScale(2, RoundingMode.HALF_UP));
        }

        jdbc.update("UPDATE purchase_orders SET total_amount = ? WHERE id = ?", total, poId);

        // M7 does not exist yet, so this UPDATE affects zero rows on any real
        // request today — accepted and applied anyway, because the contract
        // makes the promise now and a caller passing real ids later must not
        // discover it was silently ignored.
        if (request.fromRecommendationIds() != null) {
            for (UUID recommendationId : request.fromRecommendationIds()) {
                jdbc.update("UPDATE reorder_recommendations SET status = 'ordered' WHERE id = ?",
                        recommendationId);
            }
        }

        return read(poId).orElseThrow(() -> new IllegalStateException("PO vanished after insert"));
    }

    /**
     * Submits a draft: {@code draft → ordered}, stamping {@code ordered_at}.
     *
     * <p>Same shape as {@code SaleService.voidSale}'s status change and the
     * same reason: the conditional {@code UPDATE ... WHERE status = 'draft'}
     * is both the guard and the lock in one statement, so two concurrent
     * submits cannot both succeed — a read-then-write pre-check would let
     * both through.
     */
    public PurchaseOrderDetail submit(UUID poId) {
        return transactions.execute(status -> {
            int updated = jdbc.update(
                    "UPDATE purchase_orders SET status = 'ordered', ordered_at = now() "
                            + "WHERE id = ? AND status = 'draft'", poId);

            if (updated == 0) {
                String current = currentStatus(poId);
                if (current == null) {
                    throw new NotFoundException("No such purchase order");
                }
                throw new ConflictException(
                        "This purchase order is " + current + " and cannot be submitted",
                        "po-not-submittable");
            }

            return read(poId).orElseThrow(() -> new NotFoundException("No such purchase order"));
        });
    }

    private String currentStatus(UUID poId) {
        return jdbc.query("SELECT status::text AS status FROM purchase_orders WHERE id = ?",
                        (rs, i) -> rs.getString("status"), poId)
                .stream().findFirst().orElse(null);
    }

    /**
     * Keyset pagination on {@code (created_at, id)}, most recent first — same
     * two-column reasoning as {@code SaleService.list}.
     */
    public PurchaseOrderPage list(String cursor, Integer limit, String status, UUID supplierId) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        StringBuilder sql = new StringBuilder("""
                SELECT po.id, po.po_number, po.supplier_id, s.name AS supplier_name,
                       po.status::text AS status, po.ordered_at, po.expected_at, po.received_at,
                       po.total_amount, po.created_at,
                       (SELECT COUNT(*) FROM purchase_order_items poi
                        WHERE poi.purchase_order_id = po.id) AS line_count
                FROM purchase_orders po
                JOIN suppliers s ON s.id = po.supplier_id
                WHERE 1 = 1
                """);
        var args = new ArrayList<Object>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND po.status = ?::po_status");
            args.add(status);
        }
        if (supplierId != null) {
            sql.append(" AND po.supplier_id = ?");
            args.add(supplierId);
        }
        ListCursor decoded = ListCursor.decode(cursor);
        if (decoded != null) {
            sql.append(" AND (po.created_at, po.id) < (?, ?)");
            args.add(java.sql.Timestamp.from(decoded.createdAt().toInstant()));
            args.add(decoded.id());
        }
        sql.append(" ORDER BY po.created_at DESC, po.id DESC LIMIT ").append(pageSize + 1);

        List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> new Row(
                new PurchaseOrder(
                        rs.getObject("id", UUID.class),
                        rs.getString("po_number"),
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getString("status"),
                        rs.getObject("ordered_at", OffsetDateTime.class),
                        rs.getObject("expected_at", LocalDate.class),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getBigDecimal("total_amount"),
                        rs.getInt("line_count")),
                rs.getObject("created_at", OffsetDateTime.class)), args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<Row> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = hasMore && !page.isEmpty()
                ? new ListCursor(page.get(page.size() - 1).createdAt(),
                        page.get(page.size() - 1).po().id()).encode()
                : null;

        return new PurchaseOrderPage(page.stream().map(Row::po).toList(), nextCursor);
    }

    private record Row(PurchaseOrder po, OffsetDateTime createdAt) {
    }

    /** Opaque page cursor, same shape and reasoning as {@code UserDirectory}'s. */
    private record ListCursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static ListCursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                int split = raw.lastIndexOf('|');
                return new ListCursor(
                        OffsetDateTime.parse(raw.substring(0, split)),
                        UUID.fromString(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public Optional<PurchaseOrderDetail> read(UUID poId) {
        var pos = jdbc.query("""
                SELECT po.id, po.po_number, po.supplier_id, s.name AS supplier_name,
                       po.status::text AS status, po.ordered_at, po.expected_at, po.received_at,
                       po.total_amount, po.location_id, po.notes,
                       (SELECT COUNT(*) FROM purchase_order_items poi
                        WHERE poi.purchase_order_id = po.id) AS line_count
                FROM purchase_orders po
                JOIN suppliers s ON s.id = po.supplier_id
                WHERE po.id = ?
                """, (rs, i) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        rs.getString("po_number"),
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getString("status"),
                        rs.getObject("ordered_at", OffsetDateTime.class),
                        rs.getObject("expected_at", LocalDate.class),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getBigDecimal("total_amount"),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("notes"),
                        rs.getInt("line_count"),
                }, poId);

        if (pos.isEmpty()) {
            return Optional.empty();
        }
        Object[] p = pos.get(0);

        List<PurchaseOrderLine> lines = jdbc.query("""
                SELECT poi.product_id, pr.sku, pr.name, poi.quantity_ordered, poi.quantity_received,
                       poi.quantity_ordered - poi.quantity_received AS quantity_outstanding,
                       poi.unit_cost, poi.line_total
                FROM purchase_order_items poi
                JOIN products pr ON pr.id = poi.product_id
                WHERE poi.purchase_order_id = ?
                ORDER BY pr.sku
                """, (rs, i) -> new PurchaseOrderLine(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getBigDecimal("quantity_ordered"),
                        rs.getBigDecimal("quantity_received"),
                        rs.getBigDecimal("quantity_outstanding"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("line_total")), poId);

        return Optional.of(new PurchaseOrderDetail(
                (UUID) p[0], (String) p[1], (UUID) p[2], (String) p[3], (String) p[4],
                (OffsetDateTime) p[5], (LocalDate) p[6], (OffsetDateTime) p[7], (BigDecimal) p[8],
                (Integer) p[11], (UUID) p[9], (String) p[10], lines));
    }

    /**
     * A receipt number unique within the tenant, same reasoning as
     * {@code SaleService.nextSaleNumber}: an atomic {@code UPDATE ... RETURNING}
     * on the tenant's own row, not a shared sequence, so the gap between two
     * of a tenant's PO numbers cannot be read by anyone outside that tenant.
     */
    private String nextPoNumber(UUID tenantId) {
        long assigned = jdbc.queryForObject("""
                UPDATE tenants SET next_po_number = next_po_number + 1
                WHERE id = ? RETURNING next_po_number - 1
                """, Long.class, tenantId);
        return "PO-%06d".formatted(assigned);
    }

    private String readSupplierName(UUID supplierId) {
        return jdbc.query("SELECT name FROM suppliers WHERE id = ? AND deleted_at IS NULL",
                        (rs, i) -> rs.getString("name"), supplierId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No such supplier"));
    }

    private BigDecimal readProductCost(UUID productId) {
        return jdbc.query("SELECT cost_price FROM products WHERE id = ? AND deleted_at IS NULL",
                        (rs, i) -> rs.getBigDecimal("cost_price"), productId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No such product"));
    }

    private Optional<UUID> defaultLocationId() {
        return jdbc.query("SELECT id FROM locations WHERE is_default LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }
}
