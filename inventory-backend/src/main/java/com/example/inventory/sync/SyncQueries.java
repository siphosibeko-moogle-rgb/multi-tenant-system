package com.example.inventory.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.catalog.ProductCatalog;
import com.example.inventory.inventory.InventoryQueries;
import com.example.inventory.suppliers.SupplierService;
import com.example.inventory.sync.SyncDtos.SyncChanges;
import com.example.inventory.sync.SyncDtos.Tombstone;

/**
 * The delta feed behind {@code GET /sync/changes}.
 *
 * <p>Hand-written SQL over the RLS-bound pool (CLAUDE.md §4 names sync
 * explicitly alongside reporting). No {@code WHERE tenant_id = ?} anywhere —
 * the policy on {@code change_log} applies one, and T1 has no exception here:
 * nothing in this class reads a tenant from the request, and the token
 * deliberately does not carry one.
 *
 * <h2>The algorithm, in four lines</h2>
 *
 * <pre>
 *   boundary := pg_snapshot_xmin(pg_current_snapshot())   -- oldest in-flight txn
 *   page     := change_log rows with (xact_id, seq) &gt; since AND xact_id &lt; boundary
 *   next     := page drained ? (boundary, 0) : last row's (xact_id, seq)
 *   hydrate  := fetch the current state of the ids in the page
 * </pre>
 *
 * <p>{@code V12__change_log.sql} carries the full argument for why the
 * watermark is a transaction id and not a timestamp, and — the part that is
 * easy to get wrong — why the boundary must be {@code xmin} rather than
 * {@code max(xact_id)}. The one-sentence version of the second: a transaction
 * that took a lower id but commits later would otherwise be skipped forever,
 * silently.
 *
 * <h2>Read in one transaction, or the boundary is a lie</h2>
 *
 * <p>{@link #changesSince} must run inside a single transaction. The boundary
 * and the page are two statements, and on separate transactions a row could
 * commit between them: it would be below the boundary the client is about to
 * store, and absent from the page it is about to receive. That is the same lost
 * update the boundary exists to prevent, reintroduced by reading it twice.
 * {@code SyncController} is {@code @Transactional(readOnly = true)} for exactly
 * this, and it is not a performance annotation.
 */
@Service
public class SyncQueries {

    /** Entity type strings, matching V12's triggers and the contract's arrays. */
    static final String PRODUCT = "product";
    static final String CATEGORY = "category";
    static final String SUPPLIER = "supplier";
    static final String LOCATION = "location";
    static final String STOCK = "stock";

    private final JdbcTemplate jdbc;
    private final ProductCatalog catalog;
    private final SupplierService suppliers;
    private final InventoryQueries inventory;

    public SyncQueries(@Qualifier("appDataSource") DataSource appDataSource,
                       ProductCatalog catalog,
                       SupplierService suppliers,
                       InventoryQueries inventory) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.inventory = inventory;
    }

    /** One row of the change log: what changed, and where it sits in the clock. */
    private record Change(String entityType, UUID entityId, boolean deleted,
                          long xactId, long seq) {
    }

    /**
     * @param since the client's watermark; {@link SyncToken#START} for a full snapshot
     * @param limit maximum change-log rows to consider, not response entities —
     *              one {@code stock} change can hydrate into several rows
     */
    public SyncChanges changesSince(SyncToken since, int limit) {
        long boundary = safeBoundary();

        // limit + 1 to detect a further page without a second count query.
        List<Change> rows = jdbc.query("""
                SELECT entity_type, entity_id, deleted, xact_id, seq
                FROM change_log
                WHERE (xact_id, seq) > (?, ?)
                  AND xact_id < ?
                ORDER BY xact_id, seq
                LIMIT ?
                """, (rs, i) -> new Change(
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getBoolean("deleted"),
                        rs.getLong("xact_id"),
                        rs.getLong("seq")),
                since.xactId(), since.seq(), boundary, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Change> page = hasMore ? rows.subList(0, limit) : rows;

        // Draining the interval advances the watermark to the boundary, so the
        // next call skips the whole empty range rather than re-scanning it.
        // Stopping early parks it on the last row consumed instead — advancing
        // to the boundary there would skip everything this page did not reach.
        SyncToken next = hasMore
                ? new SyncToken(page.get(page.size() - 1).xactId(),
                                page.get(page.size() - 1).seq())
                : new SyncToken(boundary, 0L);

        return hydrate(page, next, hasMore);
    }

    /**
     * The newest transaction id that is safe to hand out as a watermark.
     *
     * <p>Every transaction below {@code xmin} has finished, so its rows are
     * final. Anything at or above it may still be in flight and must be left for
     * a later sync — see V12.
     *
     * <p>A long-running transaction pins this and the feed stalls rather than
     * skipping. Fewer rows, never wrong ones.
     */
    private long safeBoundary() {
        Long boundary = jdbc.queryForObject(
                "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class);
        return boundary == null ? 0L : boundary;
    }

    /**
     * Turns a page of ids into the current state of those entities.
     *
     * <p>A change-log row becomes a tombstone in two cases: it is flagged
     * {@code deleted}, or its entity is simply absent from the hydrating query.
     * The second matters — a row hard-deleted between the log read and the
     * hydrate would otherwise vanish from the response entirely, and the client
     * would keep serving it from cache forever.
     */
    private SyncChanges hydrate(List<Change> page, SyncToken next, boolean hasMore) {
        Map<String, List<UUID>> live = new LinkedHashMap<>();
        List<Tombstone> deleted = new ArrayList<>();

        for (Change change : page) {
            if (change.deleted()) {
                deleted.add(new Tombstone(change.entityType(), change.entityId()));
            } else {
                live.computeIfAbsent(change.entityType(), k -> new ArrayList<>())
                        .add(change.entityId());
            }
        }

        List<UUID> productIds = live.getOrDefault(PRODUCT, List.of());
        List<UUID> categoryIds = live.getOrDefault(CATEGORY, List.of());
        List<UUID> supplierIds = live.getOrDefault(SUPPLIER, List.of());
        List<UUID> locationIds = live.getOrDefault(LOCATION, List.of());
        List<UUID> stockProductIds = live.getOrDefault(STOCK, List.of());

        var products = catalog.findAllByIds(productIds);
        var categories = catalog.findCategoriesByIds(categoryIds);
        var locations = catalog.findLocationsByIds(locationIds);
        var supplierRows = suppliers.findAllByIds(supplierIds);
        var stock = inventory.stockForProducts(stockProductIds);

        // Anything the log said was live but the catalogue no longer holds. The
        // usual cause is a soft delete committed after the log page was read.
        addMissingAsTombstones(deleted, PRODUCT, productIds,
                products.stream().map(p -> p.id()).toList());
        addMissingAsTombstones(deleted, CATEGORY, categoryIds,
                categories.stream().map(c -> c.id()).toList());
        addMissingAsTombstones(deleted, SUPPLIER, supplierIds,
                supplierRows.stream().map(s -> s.id()).toList());
        addMissingAsTombstones(deleted, LOCATION, locationIds,
                locations.stream().map(l -> l.id()).toList());
        // `stock` is deliberately not checked: a product with no product_stock
        // row has no stock rather than a deleted one, and a tombstone would tell
        // the client to forget a product it should keep.

        return new SyncChanges(next.encode(), hasMore, products, categories,
                supplierRows, locations, stock, deleted);
    }

    private static void addMissingAsTombstones(List<Tombstone> into, String entityType,
                                               List<UUID> requested, List<UUID> found) {
        for (UUID id : requested) {
            if (!found.contains(id)) {
                into.add(new Tombstone(entityType, id));
            }
        }
    }
}
