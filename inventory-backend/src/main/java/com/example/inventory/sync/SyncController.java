package com.example.inventory.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.sync.SyncDtos.SyncChanges;

/**
 * {@code GET /sync/changes} — everything that changed since a watermark.
 *
 * <p>HTTP only (CLAUDE.md §4); the feed itself is {@link SyncQueries}.
 *
 * <h2>{@code @Transactional} here is correctness, not performance</h2>
 *
 * <p>The feed reads two things: the safe boundary, and the page below it. On
 * separate transactions a write could commit in between — landing below the
 * boundary the client is about to store, and above the page it is about to
 * receive. The row would then never be delivered, and nothing would report an
 * error. One transaction makes both reads see one snapshot.
 *
 * <p>{@code readOnly = true} is honest and also load-bearing: it documents that
 * a sync can never write, which is what lets it be replayed freely by a client
 * that is not sure whether its last request arrived.
 *
 * <h2>Role gate</h2>
 *
 * <p>Every role, including {@code viewer}. This is a cache-warming feed for
 * data each role can already read through {@code /products}, {@code /categories},
 * {@code /locations}, {@code /suppliers} and {@code /inventory} — all of which
 * are open to every role, viewer included. Gating it more tightly than the
 * endpoints it mirrors would not withhold anything; it would only force a
 * clerk's device to fetch the catalogue one page at a time and stay offline-
 * hostile.
 *
 * <p>Note what it deliberately does <strong>not</strong> carry: no sales, no
 * money, no cost or margin. The reports are owner/manager precisely because they
 * do carry those (see {@code docs/adr/reporting.md} §5), and that distinction is
 * the reason these two read endpoints are gated differently rather than
 * inconsistently.
 */
@RestController
public class SyncController {

    private final SyncQueries sync;

    /**
     * Change-log rows considered per call.
     *
     * <p>Not the contract's {@code Limit} parameter — {@code /sync/changes}
     * declares none, so this is a server-chosen page size and {@code hasMore}
     * is how a client learns to come back. 500 keeps a full first sync of a
     * typical catalogue to a handful of round trips while bounding the response
     * a phone has to parse in one go.
     *
     * <p><strong>Injectable because it has to be testable.</strong> The
     * multi-page path is where the cursor's half-open interval earns its keep:
     * a watermark parked on a real row is the only situation where {@code >}
     * versus {@code >=} changes an answer. With a hard-coded 500 no realistic
     * fixture reaches that branch, and a mutation flipping the comparison left
     * the whole sync suite green — which per CLAUDE.md §5 meant the test was
     * wrong, not the code safe. {@code SyncPagingHttpTest} sets this to 2.
     */
    @Value("${app.sync.page-size:500}")
    private int pageSize = 500;

    public SyncController(SyncQueries sync) {
        this.sync = sync;
    }

    /**
     * @param since opaque token from the previous sync; omit for a full snapshot
     */
    @GetMapping("/sync/changes")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    @Transactional(readOnly = true)
    public SyncChanges changes(@RequestParam(required = false) String since) {
        return sync.changesSince(SyncToken.decode(since), pageSize);
    }
}
