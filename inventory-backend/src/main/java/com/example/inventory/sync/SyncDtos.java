package com.example.inventory.sync;

import java.util.List;
import java.util.UUID;

import com.example.inventory.catalog.CatalogDtos.Category;
import com.example.inventory.catalog.CatalogDtos.Location;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.inventory.InventoryDtos.StockStatus;
import com.example.inventory.suppliers.SupplierDtos.Supplier;

/**
 * The {@code GET /sync/changes} response.
 *
 * <p>Every entity array reuses the DTO its own endpoint already returns —
 * {@link Product}, {@link Category}, {@link Location}, {@link Supplier},
 * {@link StockStatus} — rather than a sync-shaped copy of each. A copy would be
 * a second definition of what a product is, and CLAUDE.md §5 has caught that
 * shape three times now. It also means the generated Android client gets the
 * same model class from both paths, so a cached product and a fetched one are
 * literally the same type.
 */
public final class SyncDtos {

    private SyncDtos() {
    }

    /**
     * @param syncToken opaque; pass it back as {@code since} next time
     * @param hasMore   true when the page stopped early. The client should call
     *                  again immediately with the new token rather than wait for
     *                  the next sync interval — otherwise a large backlog drains
     *                  one page per interval and a client offline for a week
     *                  takes a week to catch up.
     * @param deleted   tombstones. A client must apply these, not just the
     *                  upserts, or a product deleted while it was offline stays
     *                  sellable on that device forever.
     */
    public record SyncChanges(
            String syncToken,
            boolean hasMore,
            List<Product> products,
            List<Category> categories,
            List<Supplier> suppliers,
            List<Location> locations,
            List<StockStatus> stock,
            List<Tombstone> deleted) {
    }

    /**
     * @param entityType one of {@code product}, {@code category}, {@code supplier},
     *                   {@code location}, {@code stock} — the same strings the
     *                   arrays above correspond to
     */
    public record Tombstone(String entityType, UUID id) {
    }
}
