# Backend Milestones

Ten milestones, ordered so that the riskiest and most structural work happens
first. Each one has a **Done when** section written as things you can actually
run — not "implemented X", but "this test passes" or "this request returns that".
If you can't demonstrate it, the milestone isn't finished.

Time estimates assume one developer working part-time. Halve them if this is
full-time work.

---

## M0 — Skeleton and pipeline
*~2–3 days*

Get an empty application running against a real database, with the migration
tooling and test harness wired up. Nothing here is interesting, and doing it
later is painful.

**Build**
- Spring Boot 4.1 project, Java 21, package `com.example.inventory`
- `docker-compose.yml` with PostgreSQL 16
- Flyway wired; `V1__baseline.sql` in `db/migration`
- Testcontainers base class (`AbstractIntegrationTest`)
- Actuator health endpoint
- Profiles: `local`, `test`, `prod` — no credentials in `application.yml`

**Done when**
- `./mvnw spring-boot:run` starts clean and `/actuator/health` returns `UP`
- `./mvnw test` spins up a container, applies all migrations, passes
- Dropping the DB and restarting rebuilds the schema with no manual steps

---

## M1 — Tenancy and authentication ★
*~1 week*

The structural milestone. Everything after this assumes tenant isolation works,
so it has to be real and proven before any feature code exists.

**Build**
- `TenantContext`, `TenantFilter`, `TenantConnectionProvider`, `TenantIdentifierResolver`
- Two datasources: the RLS-bound app pool, and an unscoped pool restricted to
`tenants` + `users` for login and migrations only
- JWT issue/verify; access token 15 min, refresh token rotated and single-use
- `POST /auth/register-tenant`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `GET /me`
- Role-based authorisation: `owner`, `manager`, `clerk`, `viewer`
- `GlobalExceptionHandler` returning RFC 9457 problem details

**Done when**
- `TenantIsolationTest` passes: two seeded tenants, and for every repository,
tenant A sees zero of tenant B's rows
- A request with **no** tenant bound returns empty results, not all results
- A forged token with another tenant's `tid` fails signature verification
- Replaying a used refresh token revokes the whole token family
- Login with a wrong password and login with an unknown email are
indistinguishable in response body and timing
- Errors come back as `application/problem+json`, never a stack trace

> If you build only one thing carefully in this project, build this. A leak found
> in week 12 means auditing every query written since week 3.

---

## M2 — Catalog and the stock ledger ★
*~1.5 weeks*

**Build**
- Products, categories, locations: CRUD, search, barcode lookup
- `StockLedgerService` — the sole writer of `stock_movements`
- `POST /inventory/adjustments`, `GET /inventory`, `GET /inventory/movements`
- Optimistic locking via ETag / `If-Match` on product updates

**Done when**
- Posting a receipt then a sale leaves `product_stock` at the correct balance
- Overselling returns 409 with `productId`, `requested` and `available` in the
problem body, and stock is unchanged afterwards
- `UPDATE`/`DELETE` on `stock_movements` raises from the trigger
- Rebuilding `product_stock` from `SUM(quantity_delta)` matches the cache exactly
- 20 concurrent sales of a 10-unit product result in 10 sold and 10 rejected —
never a negative balance
- A stale `If-Match` returns 412

---

## M3 — Vertical slice to Android ★
*~1 week*

Cut through to the client **before** the backend is finished. Token refresh,
timezones, pagination shape and error mapping all break at this seam, and finding
that out now costs a day rather than a fortnight.

**Build**
- Android project, Retrofit client generated from `docs/openapi.yaml`
- Login screen → product list → record a sale
- `TokenAuthenticator`: 401 → refresh → retry once

**Done when** — verified on the emulator unless marked otherwise

- [x] A real phone or emulator logs in, lists products, records a sale, and the
backend's stock reflects it
- [x] Killing the backend produces a readable error in the app, not a crash —
checked as part of the retry round: stopped mid-sale, readable error, then
Retry after restart recorded the sale exactly once
- [x] An oversell shows the real requested and available numbers, including
when available is zero
- [ ] **An expired access token refreshes transparently.** Not observed on a
device. `TokenAuthenticatorTest` covers the single-flight refresh and the
reuse check, and neither is a device. To check: shorten the access-token TTL
in `application-local.yml`, sign in, wait past it, then reload the list — it
should refresh with no sign-in prompt and no visible pause.
- [ ] **Two accounts in different tenants show completely different catalogs.**
Not observed on a device. `TenantIsolationTest` proves it at the database
level across 21 tables, which is the stronger guarantee, but it is not the
same statement: it says the rows are invisible, not that the app signs the
second account in cleanly and shows it nothing of the first. To check:
register a second tenant, seed it two products, and sign in as each in turn.

The two unchecked items are listed as unchecked deliberately. Both have code
and tests behind them and would very probably pass; "would probably pass" is
exactly what this milestone exists to stop anyone saying.

**Contract gaps M3 found and did NOT close**

Tightening the response schemas with `required` (CLAUDE.md §15) turned the
contract into something checkable, and `ResponseRequiredFieldsHttpTest` then
checked it against the running server. Two gaps were fixed in M3 — `POST /sales`
returning a null `available`, and a 401 with no body at all. One is left open on
purpose, because it is a feature rather than a defect:

- **`GET /products/{id}` is declared `ProductDetail` and returns `Product`.**
The missing field is `stockByLocation`, the per-location breakdown, which is
not implemented anywhere. Every other `ProductDetail` field is present.

Deliberately not fixed by loosening the contract: doing so would delete the
only written record that the per-location view was ever promised. It belongs
with the multi-location work — **M5**, alongside receiving stock into a
specific location, where the per-location view is the point rather than a
detail page ornament.

`ResponseRequiredFieldsHttpTest.productDetailIsNotYetImplemented` pins the
current behaviour and is worded to go **red** when the field appears, telling
whoever implements it to promote the endpoint to the ordinary check. The gap
cannot go quiet.

---

## M4 — Sales and returns
*~1 week*

> **Part of this landed early, in M3.** The Android slice needed a real sale
> endpoint, so `POST /sales` was pulled forward — minimally, and deliberately
> not the whole milestone. What exists now and what M4 still owes is spelled out
> below rather than left for someone to rediscover by reading the code.

**Already done (M3)**
- [x] `POST /sales` — sale, lines and one `sale` movement per line, all in one
transaction through `StockLedgerService.postWithin`. A line that oversells
rejects the whole sale.
- [x] Idempotency on `clientRequestId`, including the concurrent case: a replay
returns the original sale with 200 and moves stock once.
- [x] `soldAt` accepted from the client, defaulting to now.

**Still owed by M4 — status as of the end of M4**
- ~~`Idempotency-Key` **header** as an alternative to the `clientRequestId`
body field~~ — done. Wired as a second entry point on `SaleService.record`;
the body field wins when both are present and differ (it's the value every
existing idempotency guarantee — the unique index, the replay tests — was
already built around), the header is consulted only when the body omits it.
`IdempotencyKeyHeaderHttpTest`.
- ~~**Sale numbering.**~~ — done. `tenants.next_sale_number`, an atomic
per-tenant counter allocated by `UPDATE ... RETURNING` (`S-%06d`), not a
global sequence — a shared counter would leak a tenant's sales volume
through the gap between two of its numbers. `SaleNumberingHttpTest`.
- ~~**Void** and **partial returns**, with the restock flag for damaged
goods~~ — done. `VoidSaleHttpTest`, `ReturnSaleHttpTest`.
- ~~`GET /sales`, `GET /sales/{saleId}`~~ — done, cursor-paginated on
`(sold_at, id)` per the contract. `SalesListHttpTest`.
- Line-level `unitCost` is captured from the product's current cost. Whether a
sale should snapshot cost at the moment of sale (for margin reporting that
survives a cost change) is an M4 decision, not something M3 settled. **Still
open** — none of the work above touched it.

**Done when**
- ~~A sale where line 3 oversells persists **nothing**~~ — done in M3,
`SaleTest.aLineThatOversellsPersistsNothing`
- ~~Posting the same `clientRequestId` twice returns the original sale and moves
stock once~~ — done in M3,
`SaleTest.Idempotency.replayReturnsTheOriginalAndMovesStockOnce`
- ~~A sale recorded with a past `soldAt` lands in the right day bucket for the
tenant's timezone~~ — done in M3,
`SaleTest.aLateNightSaleLandsOnTheTenantsBusinessDay`, which also asserts the
naive UTC date would be the *wrong* day, so the test cannot pass against a
server that ignores the tenant's timezone
- ~~Voiding returns exactly the sold quantity and leaves the original sale row
intact~~ — done, `VoidSaleHttpTest.voidReturnsTheStockAndKeepsTheSale`
- ~~The `Idempotency-Key` header behaves identically to the body field~~ —
done, `IdempotencyKeyHeaderHttpTest.headerAloneSatisfiesIdempotency`

---

## M5 — Purchasing and receipts
*~1 week*

**Build**
- Purchase orders: draft → submit → receive (partial deliveries supported)
- `GoodsReceiptService` posting `purchase_receipt` movements
- Lead time observations recorded on every receipt

**Still owed by M5**
- ~~Purchase orders: draft → submit → read → list~~ — done, step 1.
`PurchaseOrderHttpTest`.
- ~~Goods receipt through `StockLedgerService`, partial deliveries
accumulating correctly~~ — done, step 2. `GoodsReceiptHttpTest`.
- ~~Lead time observations recorded on every receipt~~ — done, step 3, timed
from the FINAL receipt that closes the PO (§ below). `LeadTimeObservationHttpTest`.
- **`Idempotency-Key` on `POST /purchase-orders/{poId}/receipts`.** The
contract declares the header on this endpoint (same as it did on
`POST /sales` before M4 closed that gap), and it is currently not read at
all — unlike sales, `ReceiptRequest` has no body-level idempotency field
either, so this endpoint has **no** idempotency protection today, not a
partial one. A receipt retried after a dropped connection — the exact
scenario the header exists for — would double-credit stock and double-count
the lead-time observation. Flagged rather than silently shipped: this is a
real bug waiting for a flaky link, not a hypothetical gap.

**Done when**
- A partial receipt moves the PO to `partial` and leaves the right outstanding qty
- Receiving more than ordered returns 409
- After three receipts from one supplier, `observedLeadTime.averageDays` reflects
actual ordered→received intervals, not the value typed into the supplier form —
this proves the pipeline populates correctly. It is a lower bar than the one
M7 needs to actually *trust* the average for a reorder point; see
`docs/adr/forecasting.md` §2 for why those are different numbers (3 vs. 5) on
purpose.
- Verified: `LeadTimeObservationHttpTest` — a single receipt against a fresh
supplier leaves `observedLeadTime.sampleSize: 1` and `averageDays` equal to
the real ordered→received gap, queried over `GET /suppliers/{supplierId}`,
not incidentally through a broader assertion; a PO closed across two partial
receipts records exactly **one** observation, timed from the FINAL receipt
(§ below); three receipts from one supplier average to the real figure,
distinct from the promised `leadTimeDays` on the same supplier row.

**Which timestamp is "received," for a PO closed across several partial
deliveries — decided in step 3, not left implicit**

The FINAL receipt — the one that closes the PO to `received` — not the first
partial one. Argued in full in `GoodsReceiptService.recordLeadTimeObservation`'s
Javadoc; in short: the reorder-point formula sizes a buffer that has to last
until the shop can count on having what it ordered, not until *something*
arrives, so a supplier who ships 70% in two days and the remaining 30% three
weeks later has a 3-week lead time for planning purposes. Measuring from the
first partial receipt would report that supplier as fast and undersize the
buffer for exactly the suppliers where batch shipping makes the distinction
matter. Exactly one observation is recorded per fully-received PO — not one
per receipt call — for the same reason `docs/adr/forecasting.md` §2's sample
count is in units of orders, not events: three partial receipts of one PO are
not three independent measurements of lead time.

**Is the ADR's n≥5 threshold reachable in realistic time? — flagged, not
silently implemented**

For a supplier ordered from weekly, yes — 5 observations in ~5 weeks, well
inside the ADR's own "reachable within weeks" framing. For a supplier ordered
from **monthly**, no: 5 observations is **5 months**. That is a long time for
a new supplier relationship to keep leaning on the promised, optimistic
`leadTimeDays` instead of a measured one — most of a business's first two
quarters with that supplier. The fallback means this is a degraded-confidence
period, not a missing-reorder-point one (§2's fallback to the promised figure
still produces a number), but "degraded for 5 months" is worth someone
deciding is acceptable rather than discovering by accident. Not resolved here
— lowering the threshold, weighting recent observations more, or accepting
the wait are all real options and picking one is a forecasting-design
decision for whoever takes up M7, not an M5 implementation detail.

---

## M6 — Seed data ★
*~2–3 days*

Not glamorous, and skipping it makes M7 impossible to build or demo. A
generator that only produces well-behaved data makes M7 *look* like it works
— every shape below exists to make a specific M7 failure mode demonstrable,
not just to look varied. See `docs/adr/forecasting.md` for how M7 will use
each one.

**Build**
- A generator producing 6–12 months of synthetic history across deliberately
different demand shapes:
- **steady seller, ~18.6/week (2.65/day) with low variance** — the worked
example. **Measured, not aspirational**: the generator sells on ~87% of
days at 2–4 units, and M7 step 1's rollup reconciles that to 562 units
over 212 days against the ledger. Earlier text here said 20/week; see
`docs/adr/forecasting.md` §8 item 1 before changing it back.
- **one with a genuine stockout period** in its history, so the
`had_stockout` exclusion (`docs/adr/forecasting.md` §3) is actually
testable — a seed set with no stockout days can't tell "excludes correctly"
from "happens to average right by luck". **Measured in M7 step 1: the
outage is ~64 contiguous days, not the 5 this generator scripts** — the
40-unit opening stock drains by ~day 22 (`sellUpTo` caps each sale to
what is on the shelf), the shelf then sits empty until the scripted
refused-sale window at ~day 84 and the restock at ~day 89, so only the
last five days carry confirmed refusals and the preceding ~59 are silent
emptiness.
- **intermittent, selling on roughly 1 day in 10** — this breaks naive
averages and is what routes `MethodSelector` to Croston
- **seasonal or trending**
- **dead stock**, no sales in months — must still be seeded through a full
window; it stays `insufficient_data` forever and that's the point (§5)
- **a genuine weekly cycle** — quiet weekdays, busy weekends, flat in trend.
Added in M7 step 2, because M7 gates reorder points on
`seasonalityIndicator()` and until this existed that gate could only be
proven against a hand-constructed fixture: none of the other six shapes is
cyclical, and "seasonal or trending" below is implemented purely as a ramp.
A gate nothing real exercises is a gate nobody finds out is broken. Weekly
rather than annual because a 30-week window holds 30 weekly cycles and less
than one annual one
- **brand-new, under 2 weeks of history** — fails the readiness threshold
outright

**Command**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,seed
```

`local` for the datasource, same as an ordinary run; `seed` activates
`SeedDataRunner`, the only `@Profile` guard in this codebase's `main`
sources. Generates ~7 months of history (`SeedDataRunner.WINDOW_WEEKS = 30`)
for two brand-new tenants, logs their ids/products/an owner login for each,
and exits — nothing is idempotent here, re-running mints two more tenants
rather than updating the last pair. Drop and recreate the local database
(`docker compose down -v && docker compose up -d db`) for a clean slate.

Built as `com.example.inventory.seed.TenantSeeder`, a plain (non-gated)
`@Component` that `SeedDataRunner` calls twice — through the real services
(`TenantRegistrationService`, `ProductCatalog`, `SaleService`,
`StockLedgerService`'s adjustment path, `PurchaseOrderService`,
`GoodsReceiptService`), never a raw `INSERT` into a transactional table, with
two documented exceptions: suppliers (M5 built `GET /suppliers/{id}` only,
so there is no real mechanism to seed one through) and a purchase order's
`ordered_at` (`submit()` always stamps the real current instant by design —
see M5 — so backdating it for historical placement is a one-column `UPDATE`
after the real `submit()` call, not instead of it). Both are flagged in the
class's own Javadoc.

**Done when**
- ~~One command populates two tenants with distinct, realistic histories~~ —
done, `SeedDataRunner`. `SeedDataVerificationTest` runs the same generator
at a shorter window and checks it in ~13 seconds against an isolated
Testcontainers database.
- **`demand_daily` includes zero-demand days — NOT verified, and it is not
this milestone's to verify.** `demand_daily` has no populating mechanism at
all yet: no view, no trigger, nothing. That is M7's `DemandRollupJob`, not
built. Verified instead at the source the rollup will eventually read —
`stock_movements` — via `SeedDataVerificationTest
.steadySellerHasZeroAndNonZeroDemandDays`: the steady seller has sale
movements on some but not all calendar days in its window. Once
`DemandRollupJob` exists, re-running the rollup against this seed data and
confirming `demand_daily` itself carries zero-demand rows belongs on M7's
own "Done when" list, not retrofitted onto this one.
- ~~The stockout-period product shows visibly lower recorded sales during
its outage than its surrounding baseline~~ — done, verified one level
lower than the criterion names for the same reason as above:
`had_stockout` is a `demand_daily` column, so nothing can flag it yet.
`SeedDataVerificationTest.stockoutIsGenuine` instead confirms the
ledger-reconstructed running balance both never goes negative (T12) and
genuinely touches zero, and that a real sale attempt during the outage was
refused rather than skipped — the evidence `had_stockout` will be computed
from, once something computes it.
- ~~The intermittent product's history is seeded far enough back... that it
eventually crosses the readiness threshold~~ — done by construction: the
real seed run's 30-week window is more than double the ADR's ~14-week
floor for this shape (`docs/adr/forecasting.md` §5). Not independently
re-verified by a test beyond that arithmetic, since nothing yet computes
readiness to check it against.

**Two more, from the review that shaped this milestone:**
- ~~At least one supplier with 5+ receipts across the window~~ — done. One
supplier per tenant receives 6 purchase orders (`docs/adr/forecasting.md`
§2's n≥5 trust threshold), each with independently randomised lead time
(3–8 days), so `observedLeadTime` carries real variance, not a repeated
constant. `SeedDataVerificationTest.supplierHasEnoughObservations`.
- ~~At least one purchase order per major product~~ — done for the four
actively-selling shapes (steady, intermittent, stockout, trending); dead
stock and the brand-new product deliberately don't get one — nobody
reorders dead stock, and "just started carrying this, stocked by hand" is
brand-new's own realistic story. `SeedDataVerificationTest
.majorProductsHaveAPurchaseOrder`.

---

## M7 — Forecasting ★
*~2 weeks*

Design decisions (the reorder-point formula, the lead-time source and its
sample-count threshold, the censored-demand rule, the method-selection split,
and the exact readiness thresholds) are recorded ahead of this milestone in
`docs/adr/forecasting.md` — read it before implementing any of the bullets
below rather than re-deriving them.

**Build**
- ~~`DemandRollupJob`: movements → `demand_daily`, in the tenant's timezone~~
— done, step 1. Zero-demand days come from `generate_series` over each
product/location's calendar span, not from the days that have movements;
`had_stockout` reconstructs the running balance, because an outage is an
*absence* of rows (a refused sale writes nothing, T12). `units_sold` nets
restocked returns and floors at zero — a rule the ADR was silent on,
recorded by `V9` on the column itself. Runs on the caller's bound tenant
and never binds one itself; see **Open question** below.
- `ForecastMethod` strategy: moving average, weighted MA, exponential smoothing,
Croston for intermittent demand
- ~~`MethodSelector` choosing by data shape, not by config~~ — done, step 2.
Readiness, then `nonzero_fraction < 0.3` → Croston, then the steady split
resolved from measured trend: `|relativeTrend| > 0.5` →
`weighted_moving_average`, else `moving_average`. Stable across five RNG
seeds. `exponential_smoothing` and `ml_model` stay unselected. History is a
**bounded 12-month trailing window** (`app.forecasting.history-window-days`),
not all-history — see `docs/adr/forecasting.md` §4.
- Reorder point = `avg daily demand × lead time + safety stock`, safety stock
from demand variability and the service level (exact formula:
`docs/adr/forecasting.md` §1)
- Lead time is the observed figure from `supplier_lead_time_observations`
when the supplier has enough samples, falling back to
`suppliers.lead_time_days` otherwise (`docs/adr/forecasting.md` §2)
- `ReorderService` producing recommendations with a plain-English rationale
(template: `docs/adr/forecasting.md` §6) — required on every forecast,
including `insufficient_data` ones
- `forecast_accuracy` scored against the naive "same as last period" baseline
from day one (`docs/adr/forecasting.md` §7) — not added later once a real
model exists to justify

**Done when**
- The steady seller (~18.6/week) reports **~2.65/day** and a sensible
days-of-cover figure. That figure is measured from the seed data and
reconciled against the ledger (step 1), not derived from prose — see
`docs/adr/forecasting.md` §8 item 1 for why it is written down twice.
**Confirmed unchanged under the bounded 12-month window** (step 2): M6
seeds ~212 days, which fits inside 365, so the window trims nothing here.
Measured 2.538–2.731 across five seeds, mean ~2.66.
- The intermittent product does **not** get a naive-average forecast — the
selector routes it to Croston
- A product below the readiness threshold (`docs/adr/forecasting.md` §5 —
under 42 days of history, or fewer than 10 non-zero eligible demand days)
returns `method: insufficient_data`, a null `projectedStockoutOn`, a null
`reorderPoint`, and a populated `explanation` saying so — it must not
produce a confident number
- Days with a recorded stockout do not drag average demand down: the
stockout-period product's `avgDailyDemand` is computed with those days
excluded, not averaged in at a lower value
- A supplier with **5 or more** recorded lead-time observations produces a
reorder point from the *observed* average, not the promised
`leadTimeDays` — demonstrated by a supplier where the two figures
deliberately disagree
- A supplier with fewer than 5 observations falls back to the promised
`leadTimeDays`, and a product on that supplier gets a proportionally higher
reorder point at 21 promised days than the same product at 3
- `forecast_accuracy` rows appear after the evaluation job runs, each scored
against the naive "same as last period" baseline over the same period — not
merely computed and left uncompared
- ~~**Picked up from M6, not re-derived:** running `DemandRollupJob` against
M6's seed data produces `demand_daily` rows with genuine zero-demand days
for the steady seller, and `had_stockout: true` on the stockout product's
outage days~~ — done, step 1, `DemandRollupSeedDataTest` (30-week window,
seed 1, the same draw `SeedDataVerificationTest`'s tenant A uses).

**Observed, so steps 2–6 argue with real numbers rather than assumptions:**

| shape | days | non-zero | zero | flagged | eligible non-zero | units |
|---|---|---|---|---|---|---|
| steady | 212 | 185 | 27 | 0 | 185 | 562 |
| intermittent | 212 | 21 | 191 | 0 | 21 | 53 |
| stockout | 212 | 73 | 139 | 64 | 72 | 186 |
| trending | 212 | 154 | 58 | 0 | 154 | 324 |
| dead | 211 | 0 | 211 | 0 | 0 | 0 |
| brand-new | 11 | 5 | 6 | 0 | 5 | 6 |

- The stockout exclusion is worth **41%** on this data: the stockout
product averages **1.236/day over its 148 eligible days** and would
average **0.877/day** if the flagged days were averaged in. That is
`docs/adr/forecasting.md` §3's spiral, measurable — step 3 asserts on it.
- `nonzero_fraction` for the intermittent shape is **0.099**, which
confirms the ADR §5 table's "~week 14" prediction for it and sits well
clear of §4's 0.3 Croston line.
- Dead stock: 211 rows, all zero, **none flagged** — it has stock on the
shelf the whole time. Reading it as censored would be the censored-demand
rule firing on the one product it must not fire on.

**Two findings about M6's seed data, recorded rather than worked around:**

1. **The steady seller measures ~2.65/day (18.6/week); the prose said
~2.9/day (20/week). RESOLVED — the prose moved, the generator did not.**
That rate is the generator behaving as coded — ~87% of days × 2–4 units ≈
2.7/day — not a rollup error; the reconciliation test proves
`demand_daily`'s totals equal the ledger's own figure exactly. Changing
working seed data to match stale prose would be backwards, so the M6 and
M7 figures above were corrected instead. **This figure has now drifted
twice** — see `docs/adr/forecasting.md` §8 item 1, and read it before
"correcting" 2.65 back to anything else.
2. **The stockout product's outage is ~64 contiguous days, not the five
M6 scripts.** `sellUpTo` caps each sale to what is on the shelf, so the
40-unit opening stock is drawn down by roughly day 22 and the shelf then
sits empty until the scripted refusal window at ~day 84 and the restock
at ~day 89. Only the last five days carry the confirmed refused sales;
the preceding ~59 are silent emptiness. M6's own `stockoutIsGenuine`
passes because it checks the balance touches zero and a sale was
refused, both of which hold. Harmless for M7 — it produces *more*
censored days to test against, and the flagged run ends exactly on the
receipt's own date — but it means "the stockout period" is 30% of that
product's history rather than a brief dip.

**BLOCKING — seasonal products do not get a trustworthy reorder point, and
M7 cannot be called complete until they do.**

No method here models a cycle, so a genuinely seasonal product has its peaks
and troughs averaged flat: the reorder point is right for an average week of
the year and wrong, in both directions at different times, precisely when it
matters. `docs/adr/forecasting.md` §4 carries the detail.

This is an **open requirement, not a permanent asterisk**, and it is gated
rather than merely noted. `DemandSeries.seasonalityIndicator()` measures
detrended autocorrelation at candidate cycle lengths and, above 0.35, sets
`Selection.isSeasonalitySuspected()`; the forecast then carries an explicit
caveat **in the API response itself**, so a shop owner acting on the number
is told the number is not to be trusted for their product. It detects *that*
a cycle exists, never which one — detecting seasonality is a far cheaper
problem than forecasting it.

**The detection is now proven end to end against real generated history.**
M6 grew a seventh shape — a weekend-heavy weekly cycle — for exactly this.
Measured across five seeds it scores **0.896–0.908** while every
non-cyclical shape scores **0.026–0.189**, so the 0.35 threshold sits in the
middle of a wide gap rather than near either edge. It routes to
`moving_average`, which is the point: the method looks entirely reasonable
and averages the weekend peaks flat, and only the caveat says otherwise.

**What remains to close this** is a method with a seasonal term, so the
number itself becomes right rather than merely flagged as wrong.

**Named gap — scheduled cross-tenant rollup is not built.** Recompute is
request-bound via `POST /forecasts/recompute`; a scheduled cross-tenant
rollup needs a documented T1 exception or a per-tenant credential — not yet
built. Tracked here the same way the `Idempotency-Key` gap was tracked
before M4 closed it, and deliberately not worked around: `DemandRollupJob`
never binds a tenant and never enumerates them, so it can only run on an
already-bound thread. CLAUDE.md §12 exists precisely so nobody bolts a
bypass onto T1 under deadline pressure; the cron story is a decision to
make deliberately, not a side effect of needing a nightly job.

---

## M8 — Reporting and sync
*~1 week*

**Build**
- Dashboard, sales summary, inventory valuation (including `asOf` replay), top products
- `GET /sync/changes` delta feed; outbox replay path on the client

**Done when**
- Valuation `asOf` a past date matches a manual replay of the ledger to that date
- A sale captured with the phone in airplane mode syncs on reconnect and is
recorded exactly once
- Report endpoints stay under ~500 ms against a tenant with 100k movements

---

## M9 — Hardening
*~1 week*

**Build**
- ~~Rate limiting on auth endpoints~~ — **done in M1.** `AuthRateLimiter` covers
`/auth/register-tenant` and `/auth/login`. What remains for M9 is making it
*distributed*: it is currently a per-instance in-memory counter, so N replicas
means N× the effective limit. Needs shared state (Redis or a table).
- Audit log coverage on all mutations
- Index review against real query plans; structured logging with trace ids
- Backup/restore runbook; secrets out of the repo
- **JWT signing key rotation** (deferred from M1). Today there is one symmetric
HS256 key, no `kid` header and no JWKS, so rotating it invalidates every live
access and refresh token at once. Needed:
- a `kid` in the header so tokens name the key that signed them
- more than one key trusted for verification while only the newest signs,
so a rotation drains rather than cuts off
- asymmetric keys with a published JWKS if anything other than this
application ever has to verify a token
Note that forging this key means forging the `tid` claim, which is the whole
of tenant isolation — so key handling is an isolation concern, not just an
auth one. `SecurityConfig` refuses to start on a key shorter than 32 bytes.

**Done when**
- Brute-forcing login is throttled and logged
- The rate limit holds across a multi-replica deployment, not just per instance
- Every mutating endpoint writes an audit row with before/after state
- `EXPLAIN ANALYZE` on the three slowest reports shows index scans, not seq scans
- A restore from backup into a clean database reproduces working data
- A signing key can be rotated with no user-visible logout: tokens signed by the
previous key keep verifying until they expire, and new tokens use the new key

---

## Sequencing notes

The four starred milestones — **M1 tenancy, M2 ledger, M3 vertical slice, M7
forecasting** — are where the project's real risk sits. M6 looks skippable and
	isn't: forecasting cannot be built, tested or demonstrated against an empty
	database.
	
	If time runs short, cut scope from M5, M8 and M9 rather than from M1–M3. A
	system with fewer features but provable isolation and a correct ledger is a
	finished product. One with every feature and a leak is not.
