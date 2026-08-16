package com.example.inventory.catalog;

/**
 * A value together with the row version it was read at.
 *
 * <p>The version travels beside the DTO rather than inside it, deliberately. The
 * contract's {@code Product} schema has no {@code version} field, and HTTP
 * already has a place for exactly this: the {@code ETag} header. Putting the
 * counter in the response body as well would be a second, redundant channel for
 * the same fact — and the two could then disagree.
 *
 * <p>The alternative of adding the field and hiding it with {@code @JsonIgnore}
 * would work, but it makes the DTO's shape depend on an annotation being right,
 * where this makes it depend on nothing.
 */
record Versioned<T>(T value, long version) {

    String etag() {
        return ETags.of(version);
    }
}
