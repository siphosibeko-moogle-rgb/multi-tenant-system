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

**Still owed by M4**
- `Idempotency-Key` **header** as an alternative to the `clientRequestId` body
field. The contract declares the header on this endpoint and it is currently
ignored — only the body field is honoured.
- **Sale numbering.** M3 generates `S-<millis>-<random>` purely to satisfy the
`(tenant_id, sale_number)` unique constraint. It is not a receipt number a
human would read out over the phone, and it has no per-tenant sequence.
- **Void** and **partial returns**, with the restock flag for damaged goods.
- `GET /sales`, `GET /sales/{saleId}` — the contract declares them; only POST
is implemented.
- Line-level `unitCost` is captured from the product's current cost. Whether a
sale should snapshot cost at the moment of sale (for margin reporting that
survives a cost change) is an M4 decision, not something M3 settled.

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
- Voiding returns exactly the sold quantity and leaves the original sale row intact
- The `Idempotency-Key` header behaves identically to the body field

---

## M5 — Purchasing and receipts
*~1 week*

**Build**
- Purchase orders: draft → submit → receive (partial deliveries supported)
- `GoodsReceiptService` posting `purchase_receipt` movements
- Lead time observations recorded on every receipt

**Done when**
- A partial receipt moves the PO to `partial` and leaves the right outstanding qty
- Receiving more than ordered returns 409
- After three receipts from one supplier, `observedLeadTime.averageDays` reflects
actual ordered→received intervals, not the value typed into the supplier form

---

## M6 — Seed data ★
*~2–3 days*

Not glamorous, and skipping it makes M7 impossible to build or demo.

**Build**
- A generator producing 6–12 months of synthetic history across deliberately
different demand shapes:
- steady seller (~7–8/week, low variance) — the worked example from the brief
- seasonal / trending product
- intermittent (sells on maybe 1 day in 10) — this breaks naive averages
- dead stock (no sales in months)
- a product with a stockout period in its history
- a brand-new product with 5 days of data

**Done when**
- One command populates two tenants with distinct, realistic histories
- `demand_daily` includes zero-demand days — verify this explicitly, it is the
single most common forecasting bug

---

## M7 — Forecasting ★
*~2 weeks*

**Build**
- `DemandRollupJob`: movements → `demand_daily`, in the tenant's timezone
- `ForecastMethod` strategy: moving average, weighted MA, exponential smoothing,
Croston for intermittent demand
- `MethodSelector` choosing by data shape, not by config
- Reorder point = `avg daily demand × lead time + safety stock`, safety stock
from demand variability and the service level
- `ReorderService` producing recommendations with a plain-English rationale

**Done when**
- The steady seller reports ~1.1/day and a sensible days-of-cover figure
- The intermittent product does **not** get a naive-average forecast — the
selector routes it to Croston
- The 5-day-old product returns `method: insufficient_data`, a null stockout
date, and low confidence — it must not produce a confident number
- Days with a recorded stockout do not drag average demand down
- A product whose supplier has a 21-day lead time gets a proportionally higher
reorder point than the same product with a 3-day supplier
- `forecast_accuracy` rows appear after the evaluation job runs

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
