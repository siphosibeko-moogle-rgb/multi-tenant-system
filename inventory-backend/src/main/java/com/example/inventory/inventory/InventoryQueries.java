package com.example.inventory.inventory;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.inventory.InventoryDtos.MovementPage;
import com.example.inventory.inventory.InventoryDtos.StockMovement;
import com.example.inventory.inventory.InventoryDtos.StockStatus;
import com.example.inventory.inventory.InventoryDtos.StockStatusPage;

/**
 * Reads over the ledger and the cached balances.
 *
 * <p>Hand-written SQL rather than JPA, per CLAUDE.md §4: these are aggregates and
 * joins over a ledger, and expressing them through an ORM makes them slower and
 * harder to read without making them safer. Tenant scoping comes from RLS, not
 * from a {@code WHERE tenant_id = ?} in any of these statements — none of them
 * has one.
 */
@Service
public class InventoryQueries {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public InventoryQueries(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** The tenant's default location, used when a request omits one. */
    public Optional<UUID> defaultLocationId() {
        return jdbc.query("SELECT id FROM locations WHERE is_default LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    /**
     * The ledger, newest first.
     *
     * <p>Cursor on {@code (occurred_at, id)} descending. Both columns: many
     * movements share a timestamp — a sale writes one per line in the same
     * transaction — and a cursor on {@code occurred_at} alone would drop or
     * repeat whichever of them straddled a page boundary.
     */
    public MovementPage movements(String cursor, Integer limit, UUID productId,
                                  UUID locationId, String type,
                                  OffsetDateTime from, OffsetDateTime to) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<Object> args = new ArrayList<>();

        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.product_id, p.name AS product_name, m.location_id,
                       m.movement_type::text AS movement_type, m.quantity_delta, m.unit_cost,
                       m.reference_type, m.reference_id, m.reason, m.occurred_at,
                       m.created_by, u.full_name AS created_by_name
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                LEFT JOIN users u ON u.id = m.created_by
                WHERE 1 = 1
                """);

        if (productId != null) {
            sql.append(" AND m.product_id = ?");
            args.add(productId);
        }
        if (locationId != null) {
            sql.append(" AND m.location_id = ?");
            args.add(locationId);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND m.movement_type = ?::movement_type");
            args.add(type);
        }
        if (from != null) {
            sql.append(" AND m.occurred_at >= ?");
            args.add(java.sql.Timestamp.from(from.toInstant()));
        }
        if (to != null) {
            sql.append(" AND m.occurred_at <= ?");
            args.add(java.sql.Timestamp.from(to.toInstant()));
        }

        MovementCursor decoded = MovementCursor.decode(cursor);
        if (decoded != null) {
            sql.append(" AND (m.occurred_at, m.id) < (?, ?)");
            args.add(java.sql.Timestamp.from(decoded.occurredAt().toInstant()));
            args.add(decoded.id());
        }

        sql.append(" ORDER BY m.occurred_at DESC, m.id DESC LIMIT ").append(pageSize + 1);

        List<StockMovement> rows = jdbc.query(sql.toString(), (rs, i) -> new StockMovement(
                rs.getLong("id"),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_name"),
                rs.getObject("location_id", UUID.class),
                rs.getString("movement_type"),
                rs.getBigDecimal("quantity_delta"),
                // balanceAfter is not stored per row. Reconstructing it would mean
                // a window function over the whole ledger for the product, which
                // is a reporting concern (M8), not a list concern. Null is the
                // contract's documented value.
                null,
                rs.getBigDecimal("unit_cost"),
                rs.getString("reference_type"),
                rs.getObject("reference_id", UUID.class),
                rs.getString("reason"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name")), args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<StockMovement> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore && !page.isEmpty()
                ? new MovementCursor(page.get(page.size() - 1).occurredAt(),
                        page.get(page.size() - 1).id()).encode()
                : null;

        return new MovementPage(page, next);
    }

    /** Stock status per product/location, from the {@code v_stock_status} view. */
    public StockStatusPage stock(String cursor, Integer limit, UUID locationId, String stockState) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<Object> args = new ArrayList<>();

        StringBuilder sql = new StringBuilder("""
                SELECT s.product_id, s.sku, s.name, s.location_id, l.name AS location_name,
                       s.quantity_on_hand, ps.quantity_reserved, s.quantity_available,
                       s.effective_reorder_point, s.stock_state, s.days_of_cover,
                       ps.updated_at
                FROM v_stock_status s
                JOIN locations l ON l.id = s.location_id
                JOIN product_stock ps
                     ON ps.product_id = s.product_id AND ps.location_id = s.location_id
                WHERE 1 = 1
                """);

        if (locationId != null) {
            sql.append(" AND s.location_id = ?");
            args.add(locationId);
        }
        if (stockState != null && !stockState.isBlank()) {
            sql.append(" AND s.stock_state = ?");
            args.add(stockState);
        }

        String decoded = decodeText(cursor);
        if (decoded != null) {
            sql.append(" AND (s.sku, s.product_id::text) > (?, ?)");
            args.add(decoded.substring(0, decoded.lastIndexOf('|')));
            args.add(decoded.substring(decoded.lastIndexOf('|') + 1));
        }

        sql.append(" ORDER BY s.sku, s.product_id LIMIT ").append(pageSize + 1);

        List<StockStatus> rows = jdbc.query(sql.toString(), (rs, i) -> new StockStatus(
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_name"),
                rs.getBigDecimal("quantity_on_hand"),
                rs.getBigDecimal("quantity_reserved"),
                rs.getBigDecimal("quantity_available"),
                rs.getBigDecimal("effective_reorder_point"),
                rs.getString("stock_state"),
                rs.getBigDecimal("days_of_cover"),
                rs.getObject("updated_at", OffsetDateTime.class)), args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<StockStatus> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore && !page.isEmpty()
                ? encodeText(page.get(page.size() - 1).sku() + "|"
                        + page.get(page.size() - 1).productId())
                : null;

        return new StockStatusPage(page, next);
    }

    /**
     * Every location's stock row for the given products, for
     * {@code GET /sync/changes}.
     *
     * <p>Reads {@code v_stock_status} — the same view {@link #stock} pages over
     * — so the sync feed and the Stock screen cannot disagree about a product's
     * state. Mirroring the view's CASE here instead would be the third instance
     * of the bug CLAUDE.md §5 keeps recording.
     *
     * <p>Returns <em>all</em> locations for each product because
     * {@code change_log} keys stock changes on {@code product_id} alone —
     * V12 explains why (the contract's tombstone carries one uuid, and
     * {@code product_stock} is keyed on a pair). Over-fetching a multi-location
     * tenant's other rows is correct; guessing which half of the key changed
     * would not be.
     */
    public List<StockStatus> stockForProducts(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(productIds.size(), "?"));
        return jdbc.query("""
                SELECT s.product_id, s.sku, s.name, s.location_id, l.name AS location_name,
                       s.quantity_on_hand, ps.quantity_reserved, s.quantity_available,
                       s.effective_reorder_point, s.stock_state, s.days_of_cover,
                       ps.updated_at
                FROM v_stock_status s
                JOIN locations l ON l.id = s.location_id
                JOIN product_stock ps
                     ON ps.product_id = s.product_id AND ps.location_id = s.location_id
                WHERE s.product_id IN (%s)
                ORDER BY s.sku, s.product_id, s.location_id
                """.formatted(placeholders), (rs, i) -> new StockStatus(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("location_name"),
                        rs.getBigDecimal("quantity_on_hand"),
                        rs.getBigDecimal("quantity_reserved"),
                        rs.getBigDecimal("quantity_available"),
                        rs.getBigDecimal("effective_reorder_point"),
                        rs.getString("stock_state"),
                        rs.getBigDecimal("days_of_cover"),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                productIds.toArray());
    }

    /**
     * Rebuilds a product's balance from the ledger.
     *
     * <p>Exists so {@code product_stock} can be checked against the rows it is
     * derived from — the cache is only trustworthy if it can be shown to agree
     * with {@code SUM(quantity_delta)}, and the milestone asks for exactly that.
     */
    public BigDecimal rebuiltBalance(UUID productId, UUID locationId) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private record MovementCursor(OffsetDateTime occurredAt, long id) {
        String encode() {
            return encodeText(occurredAt + "|" + id);
        }

        static MovementCursor decode(String encoded) {
            String raw = decodeText(encoded);
            if (raw == null) {
                return null;
            }
            try {
                int split = raw.lastIndexOf('|');
                return new MovementCursor(
                        OffsetDateTime.parse(raw.substring(0, split)),
                        Long.parseLong(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static String encodeText(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Null for absent or unreadable — a made-up cursor starts from the beginning. */
    private static String decodeText(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
