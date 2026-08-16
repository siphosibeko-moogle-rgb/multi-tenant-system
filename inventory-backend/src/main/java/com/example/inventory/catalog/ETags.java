package com.example.inventory.catalog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.example.inventory.web.PreconditionFailedException;

/**
 * ETags for product reads and {@code If-Match} on product writes.
 *
 * <h2>What the ETag is derived from</h2>
 *
 * <p>{@code products.updated_at}, rendered as microseconds since the epoch.
 * V1's {@code trg_products_updated} sets it on every UPDATE, so it advances
 * whenever the row changes — which is precisely the property a version needs.
 *
 * <p>A dedicated {@code version bigint} column would be the textbook answer and
 * is what {@code product_stock} already has. {@code products} does not have one,
 * and adding it means a migration; {@code updated_at} carries the same
 * information for this purpose without one. The trade-off is worth stating
 * plainly:
 *
 * <ul>
 *   <li>PostgreSQL's {@code now()} is the <em>transaction</em> timestamp, so two
 *       updates to one row inside a single transaction produce the same
 *       {@code updated_at} and therefore the same ETag. Across requests — which
 *       is the case If-Match exists for — every update lands in its own
 *       transaction and the value advances.</li>
 *   <li>Microsecond resolution makes an accidental collision between two
 *       separate transactions vanishingly unlikely, and the concurrent case does
 *       not rely on it anyway: the second writer's UPDATE is compared against
 *       the row the first writer committed, whatever timestamp it carries.</li>
 * </ul>
 *
 * <p>If strict versioning is wanted later, a {@code version} column in a
 * migration is the change, and only this class and the UPDATE's WHERE clause
 * move.
 *
 * <h2>Strong, not weak</h2>
 *
 * <p>{@code W/} would say "semantically equivalent", which is not the claim
 * being made: this is a byte-for-byte identity of one row version, used to
 * decide whether a write is based on a current read.
 */
final class ETags {

    private ETags() {
    }

    static String of(OffsetDateTime updatedAt) {
        if (updatedAt == null) {
            return null;
        }
        Instant instant = updatedAt.toInstant();
        long micros = instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
        return "\"" + micros + "\"";
    }

    /**
     * Reads an {@code If-Match} header back into the timestamp it encodes.
     *
     * <p>A malformed value is rejected with 412 rather than ignored. Ignoring it
     * would silently downgrade the request to last-write-wins — the caller asked
     * for a conditional write and would be told it succeeded conditionally when
     * no condition was applied.
     */
    static OffsetDateTime parse(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();

        // "*" means "any current representation" — a conditional write that
        // always passes, which is a valid thing to ask for.
        if ("*".equals(value)) {
            return null;
        }
        try {
            long micros = Long.parseLong(value);
            return OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L),
                    ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException(
                    "If-Match is not an ETag this server issued");
        }
    }
}
