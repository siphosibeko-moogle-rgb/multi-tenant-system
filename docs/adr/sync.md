# ADR — Sync and the offline outbox (M8)

Status: accepted, M8.
Scope: `GET /sync/changes`, `change_log` (V12), adjustment idempotency (V13),
and the Android outbox in `android/.../mobile/offline/`.

Read this before changing anything in `sync/` or in `offline/`. The whole
subject is a set of failures that produce no error, no crash and no log line —
a missed row means one device serves stale data forever, a repeated row means a
client that never stops syncing, and a replayed sale means stock taken twice.
None of them throw.

---

## 1. The change token is a transaction id, not a timestamp

`updated_at > X` is the obvious implementation and it is wrong three separate
ways.

**It ties.** `now()` is the *transaction* timestamp, so every row a batch
touches shares it exactly — the same property that made V4 add a version column
rather than derive an ETag from `updated_at`. A client that syncs at that
timestamp must either re-fetch those rows forever (`>=`) or skip the ones it
has not seen (`>`).

**It can loop forever.** With `>=` and a page limit, a tenant whose writes are
dense enough that one timestamp fills a page never advances: every sync returns
the same page, the watermark never moves, and the client burns battery
re-reading the same rows.

**Clocks move backwards.** NTP corrections, leap smearing and a restored
replica all rewind `now()`. Rows written during the rewound interval sort
before a watermark the client already holds and are never delivered.

So every logged change records `pg_current_xact_id()` — 64-bit, monotonic,
non-wrapping. A logical clock that never ties and never goes backwards.

### The part that is easy to get wrong

A monotonic id is necessary and **not sufficient**, and a plain sequence has
exactly the same hole:

> Transaction A takes id 100. Transaction B takes id 101. **B commits first.**
> A sync running in between sees `max(id) = 101`, hands the client a token of
> 101, and returns B's row. Then A commits. A's row has id 100, which is below
> the client's watermark, so it is never sent. The row is lost forever and
> nothing anywhere reports an error.

The fix is to refuse to hand out a watermark past any transaction that might
still be in flight. `pg_snapshot_xmin(pg_current_snapshot())` is exactly that
number: the oldest transaction still running. Everything below it has committed
or aborted, so its rows are final.

```
boundary := pg_snapshot_xmin(pg_current_snapshot())
page     := change_log rows with (xact_id, seq) > since AND xact_id < boundary
next     := page drained ? (boundary, 0) : last row's (xact_id, seq)
```

In the gap scenario the boundary is 100 while A runs, so the sync returns
nothing above it and B's row waits. When A commits, the next boundary rises
past both and delivers them in id order. Nothing skipped, nothing duplicated.

The interval is **half-open** and the next `since` is exactly the previous
`boundary`, which is what makes the ranges tile rather than overlap.

**The cost, stated so nobody is surprised by it:** a long-running transaction
holds `xmin` down and stalls the feed until it ends. Sync returns fewer rows
rather than wrong ones — the safe direction, and the same trade a long
transaction already imposes on autovacuum.

### Why the token has two fields

`(xact_id, seq)`. One transaction changing 500 products writes 500 rows sharing
an `xact_id`, so a page ending inside that transaction could not be resumed:
the next request would either repeat the whole transaction or skip the rest.
Exactly why `/users` pages on `(created_at, id)` and not on `created_at`.

### Why it is opaque

Base64, like every other cursor here, with a version prefix so a future change
to the encoding can reject an old token loudly instead of misreading it as a
position. A client that parses one is broken by any change to the ordering —
and a client that does arithmetic on a token that *looks* like a number is
broken the first time two writes share a transaction.

It is **not** a security boundary. A caller can decode and forge one; that is
harmless, because the feed is tenant-scoped by RLS on the connection and a
forged token can only make a client miss its own rows or re-read them. It
carries no tenant id — nothing here reads identity from the request (T1).

---

## 2. The feed must be read in one transaction

`SyncController` is `@Transactional(readOnly = true)` and that is **correctness,
not performance**.

The boundary and the page are two statements. On separate transactions a row
can commit between them: it would be below the boundary the client is about to
store, and absent from the page it is about to receive. That is the same lost
update the boundary exists to prevent, reintroduced by reading it twice.

---

## 3. `change_log` holds the latest change per entity, not every change

One row per `(tenant, entity_type, entity_id)`, upserted. A client
synchronising a cache wants the current state of what changed, never the
history of how it got there — so a product edited fifty times is one row. The
alternative grows without bound and needs a retention job that would silently
break the feed for anyone offline longer than the retention window.

The consequence to know: this is a **current-state index**, so a client offline
for a year and one offline for a minute are served by the same mechanism, and
neither can be given a change that has since been superseded. Correct for a
cache, wrong for an audit trail. `audit_log` is the audit trail.

`stock` is keyed on `product_id` rather than the `(product, location)` pair
`product_stock` actually has, because the contract's tombstone carries a single
uuid and there is nowhere to put the second half. The feed therefore returns
every location's row for a product whose stock moved anywhere. That
over-fetches for a multi-location tenant and is correct; keying on half a
composite and hoping would not be.

### `locations` was added to the contract

The feed shipped with `products`, `categories`, `suppliers` and `stock` but not
`locations` — so an offline client could not refresh which locations exist, and
a sale needs one. `StockStatus` carries `locationId` and `locationName`, so
names leaked in through stock rows, but only for products that happened to
change. Purely additive; no existing client breaks.

---

## 4. The outbox: one idempotency mechanism, and it is the server's

Every queued entry carries the `clientRequestId` minted **when the user tapped
save** — not when the request is sent. M3 already did this for the online retry
path; the outbox is that same field surviving a process death.

The client does **no** deduplication of its own, keeps no "already sent" set,
and does not try to guess whether a request arrived. It cannot: a request whose
response was lost is indistinguishable, from the device, from one that never
left. Only the server can tell, and it does — with a 200 instead of a 201.

`capturedAt` is likewise from the tap. The tenant's daily rollup buckets on it,
so replaying with "now" would move a sale made at 23:59 into the next business
day.

### Deferred versus rejected

The classification is the hard part, and collapsing the two buckets is how
clients either retry a doomed request forever or silently discard a real sale.

| Response | Bucket | Why |
|---|---|---|
| 200 / 201 | resolved | recorded; 200 means an earlier attempt got through |
| no response | deferred | still offline; it may or may not have landed |
| 401 | deferred | session expired — the sale must outlive a re-login |
| 408, 429, 5xx | deferred | the server's problem, not the request's |
| any other 4xx | **rejected** | the request itself will never work |

A 409 on a queued sale is the interesting rejection: the product was oversold
through another till while the phone was offline. The sale genuinely cannot be
recorded, and the cashier has already handed over goods — so it is removed from
the queue **and surfaced**. Retrying forever would hide it; dropping it
silently would lose the record of money taken.

### Attempts are counted, never capped

An entry leaves the queue because the server accepted it, or because the server
refused it for a reason retrying cannot fix. **Never because it has been tried
enough times.** Discarding a real sale after N attempts loses money silently,
which is worse than a queue that will not drain — a queue that will not drain
is at least visible.

### The key is kept after queueing

The first cut cleared `pendingRequestId` once a sale was queued, and that broke
M3's deliberate behaviour: the ViewModel holds the key across a network failure
so an in-place Retry resends *the same* sale. With it cleared, a cashier who
tapped Retry minted a fresh key, and the queued copy plus the retried copy were
two different sales. Double-sold stock, introduced by the feature meant to
prevent it. Two of M3's own tests caught it.

---

## 5. V13: adjustments had no idempotency, and the contract said they did

`Idempotency-Key` has been declared on `POST /inventory/adjustments` since v1.
The implementation ignored it, so CLAUDE.md §4's "any endpoint that moves stock
or money accepts it" was true of `POST /sales` and false here.

A stated convention that is half-implemented is worse than either alternative,
because the statement is what a reader checks instead of the code — and the
reader here is whoever writes the outbox.

It was harmless while adjustments were made by a person tapping a button on a
connected phone. It stops being harmless the moment a queued adjustment can be
replayed: a lost response means a **second movement** in an append-only ledger
(T4), repairable only by a compensating row somebody must first notice is
needed.

Same mechanism as sales, not a new one: a partial unique index
(`stock_movements_idempotency_uq`), per tenant because two businesses can
generate the same UUID, and both a read-first path and a duplicate-key catch —
the second is not redundant, because two replays in flight both find nothing,
both insert, and one loses on the index.

---

## 6. What is deliberately not here

- **No conflict resolution, because nothing in scope needs one.** The outbox
  carries sales and stock adjustments, both append-only ledger writes
  adjudicated by the trigger. There is no last-write-wins decision anywhere.
  Offline *edits* to products would raise a real two-device conflict and are
  deliberately out of scope; online edits already refuse stale writes via
  ETag/`If-Match`.
- **No sales in the down-feed.** They travel up through `POST /sales`. A sale
  made on one device is therefore invisible to a second device until that
  device is online — a real limitation, and the alternative is a second
  definition of a sale's history.
- **No background scheduler.** Replay is driven by the app, not by a job. A
  cross-tenant scheduled sync would need the same decision §12 defers for the
  forecasting rollup, and it is not made here.
- **No retention job on `change_log`.** §3 explains why it does not need one.
