# ADR — Reporting (M8)

Status: accepted, M8.
Scope: `GET /reports/dashboard`, `/reports/sales-summary`,
`/reports/inventory-valuation`, `/reports/top-products`.

Read this before changing any number in `reporting/`. Four of these numbers
have a real decision behind them rather than an obvious implementation, and
every one of them is a figure a shop owner will act on.

---

## 0. One definition of "revenue", used by all four endpoints

The four endpoints answer overlapping questions. M7 was bitten twice by
per-component tests missing a cross-component disagreement (CLAUDE.md §5), and
the specific shape of the second one — `GET /products` and `GET /inventory`
disagreeing about `stockState` because their *inputs* diverged — is exactly the
shape available here: `salesTotal` on the dashboard and `revenue` in a
sales-summary bucket are the same question asked twice.

So there is one SQL definition, `ReportQueries.NET_LINES`, and every money
figure in every one of the four endpoints is derived from it. It is a Java
constant spliced into each statement rather than a database view, so that no
new object needs a grant, a policy, or a place in T11's three sweeps.

A **net sale line** is one row of `sale_items`, with:

| Term | Definition |
|---|---|
| `net_quantity` | `quantity` − everything returned against that sale and product |
| `net_revenue` | the line's share of the sale, **excluding tax**, after both the line discount and the sale-level discount, scaled to `net_quantity` |
| `net_cost` | `sale_items.unit_cost` (the snapshot — §1) × `net_quantity` |
| gross profit | `net_revenue` − `net_cost` |

and three exclusions:

- **Voided sales are excluded entirely** (`status <> 'voided'`). A void is not a
  sale that happened and was reversed; commercially it did not happen.
  V5 also records a void as a `sale_returns` group covering everything
  outstanding, so a voided sale nets to zero even without the status filter —
  the filter is a second, cheaper guard, and the two cannot disagree.
- **Returns are netted, not counted separately.** A return reduces revenue,
  units and cost on the line it came back against, on the *sale's* date, not the
  return's. So "what did Tuesday earn" answers with what Tuesday actually kept.
  Recording returns on their own date would make a report of a closed period
  change after the fact.
- **Tax is excluded.** `revenue` is ex-tax throughout. Two reasons: tax
  collected is not the shop's money, and `grossProfit` is only meaningful
  against an ex-tax revenue — a margin computed against a tax-inclusive total
  is wrong by the tax rate.

> **The one thing to notice about the tax decision**: a shop owner comparing
> `salesTotal` against their till roll will see a *smaller* number, because the
> till roll includes VAT. That is correct and it is also surprising. The label
> on the Android tile should read "net sales", not "sales". If this is ever
> flipped to tax-inclusive it must be flipped in `NET_LINES` — one expression —
> and `grossProfit` must then stop being computed against it, because the two
> would no longer be comparable.

`salesCount` counts distinct non-voided sales in the window. A fully refunded
sale still counts: it happened, someone was served, and a count of zero for a
day that had ten transactions and ten refunds would describe the shop wrongly.

### Rolling windows, in the tenant's timezone

`period` is `today` | `week` | `month`, and every one of them is **rolling and
ends now**: today = since local midnight, week = the last 7 local days
including today, month = the last 30 local days including today.

Calendar periods were rejected: a calendar week makes Monday morning's
dashboard look like the business collapsed, and a calendar month does the same
on the 1st, every month, for every tenant. A rolling window is never empty for
a reason that is only about the date.

Local means `tenants.timezone`, resolved in SQL — the same way
`DemandRollupJob` does it, because a report and the demand history it is read
next to must not disagree about where a day ends.

The window bounds are computed as dates in the tenant's zone and then converted
*back* to `timestamptz` before they touch `sales.sold_at`. That is a
performance decision, not a style one: `WHERE (sold_at AT TIME ZONE z)::date >= …`
cannot use `sales_tenant_time_idx` and degrades to a full scan of the tenant's
sales history, which is precisely the thing M8's "under ~500 ms at 100k
movements" criterion is about.

---

## 1. `grossProfit` reads the cost snapshot, never the current cost price

`sale_items.unit_cost` is written at sale time from `products.cost_price`
(M4, `SaleService.record`). Reporting reads that column and never joins
`products` for a cost.

Why this is the only defensible option: a supplier raising their price in
August must not change what June earned. If the report read `products.cost_price`
fresh, every historical margin would silently move every time anyone edited a
product — last month's report would give a different answer today than it gave
last month, with nothing in the system recording that it had changed. A report
that is not reproducible is not a report.

This is also why the snapshot exists at all; M4 took the decision, and M8's job
is to honour it rather than re-litigate it.

Asserted by `CostSnapshotHttpTest`: sell at a known cost, then change the
product's `cost_price` to something very different, then re-read the report and
show `grossProfit` did not move — and, in the same test, that a *new* sale
picks the new cost up, so the test cannot pass by the report simply ignoring
cost altogether.

**Null `unit_cost`.** The column is nullable and `SaleService` always populates
it, so in practice this is only reachable for rows written outside the service.
Such a row is treated as zero cost, which reports its margin as 100%. The
alternative — dropping the line — would make revenue and profit disagree about
which sales exist, which is worse and harder to notice.

---

## 2. `inventoryValue` and `asOf`: the quantities are a real replay, the prices are not

**Current state (dashboard `inventoryValue`, and the valuation endpoint with no
`asOf`)**: `SUM(product_stock.quantity_on_hand × products.cost_price)` over
tracked, active, undeleted products. Current quantity, current cost. This is a
statement about right now — what is on the shelf and what it would cost to
replace — so reading today's price is correct here in the same way reading the
snapshot is correct in §1. They are answers to different questions.

`totalRetailValue` is the same sum against `selling_price`, ex-tax.

Negative balances (possible for products with `allow_negative_stock`) are
summed signed rather than clamped: a negative balance is stock already sold
that has not been received, and it genuinely reduces what is held.

**`asOf` in the past: a real replay, not an approximation.**

`quantity_on_hand` at a past date is reconstructed as
`SUM(stock_movements.quantity_delta)` over every movement whose `occurred_at`
precedes the end of that local day. This is exact, not an estimate: the ledger
is append-only (T4), `product_stock` is nothing but the running fold of it
(T5), and `LedgerCriteriaTest.rebuildFromTheLedgerMatchesTheCache` already
holds the two to agreement. Replaying the fold to an earlier point is the same
arithmetic stopped early. M8's "Done when" asks for exactly this and
`ValuationReplayHttpTest` performs the manual replay independently and compares.

The replay uses `occurred_at`, business time, not `created_at`. A backdated
correction belongs to the day it says it happened; that is what `occurred_at`
is for, and it is what the demand rollup uses.

**What is NOT replayed, stated plainly: the prices.** `products.cost_price` and
`selling_price` have no history in this schema — no price-history table, no
temporal columns — so a valuation `asOf` last March values *last March's
quantities* at *today's prices*. That is a hybrid, and it is documented in the
endpoint's OpenAPI description rather than left for a reader to discover.

It was not fixed here because fixing it properly is a costing decision, not a
query: `stock_movements.unit_cost` carries a per-movement cost snapshot and
could support a moving-average or FIFO cost as-of, but it is nullable (an
adjustment need not carry one), and choosing between weighted-average and FIFO
changes reported profit permanently. That is a milestone, not a footnote. What
is not acceptable is doing it silently, so the caveat is in the contract.

`productCount` counts the products with a non-zero balance at the valuation
date — the number of distinct products making up the figure.

**`locationId` defaults to ALL locations, which contradicts the shared
parameter's description.** `components/parameters/LocationIdQuery` says
"Defaults to the tenant's default location". Flagged rather than silently
resolved (CLAUDE.md §1): that default was written for stock *listings*, where
showing one location is a reasonable view. For a valuation it is a wrong
number that does not say it is wrong — a two-location shop asking "what is my
stock worth" would be told the worth of some of it. The parameter is therefore
inlined on this path with its own description. Most tenants have one location,
so the two readings agree in practice; where they differ, "all" is the reading
that cannot mislead.

---

## 3. `salesTrend` is a fixed 14 days, and does not follow `period`

The contract fixes no window for `salesTrend`. It is **the last 14 local days,
ending today, one point per day, including days with no sales**.

Why 14 and not 7: two full weeks means every weekday appears twice, so a
Saturday spike can be read against last Saturday. Seven days shows each weekday
once, which makes an ordinary weekly cycle indistinguishable from a trend —
and the seeded `seasonal` product exists precisely because weekly cycles in
this domain are real (`docs/adr/forecasting.md` §4). Thirty days on a phone
gives ~10px per bar.

Why it does **not** follow `period`: `period=today` would produce a
single-point "trend", which is not one. Making a chart's usefulness depend on
an unrelated dropdown is a worse property than the response containing two
different windows, which is why the field's OpenAPI description now says which
window it is.

Days with no sales are emitted as zero rather than omitted, from a
`generate_series` calendar. A chart that closes the gap draws a line between
two non-adjacent days and shows a trend that did not happen.

**Not configurable.** Nothing asks for it, and a `trendDays` parameter is a
one-line addition on the day something does.

---

## 4. `topProducts` ranks by units sold, on both endpoints

Both `GET /reports/top-products` and the dashboard's `topProducts` rank by
`unitsSold` descending (`best`) or ascending (`worst`), tie-broken by revenue
then product id so the order is stable across calls.

Revenue was the other candidate and was rejected for three reasons:

1. **The dashboard's schema carries only `unitsSold`.** Ranking by revenue there
   would produce a list ordered by a key the response does not contain, which on
   screen is indistinguishable from a list that failed to sort.
2. **"Best and worst movers"** — the contract's own summary for the endpoint —
   is inventory language about movement. Fast and slow movers are units.
3. **`order=worst` is for finding dead stock**, and revenue answers that
   question wrongly: a rarely-sold expensive item ranks mid-table by revenue
   while sitting on the shelf for a year.

Revenue and `grossMarginPct` are still reported on `/reports/top-products`, so
an owner can see both; the ranking key is simply not the money one.
`grossMarginPct` is `(revenue − cost) / revenue × 100`, and is **null** when
revenue is zero — it is not required by the contract, and reporting 0% for a
product that sold nothing would be a claim about a margin that does not exist.

**`order=worst` includes products that sold nothing at all.** They are the worst
movers, and an aggregate over `sale_items` cannot see them because they have no
rows there. The query left-joins the catalogue so a zero-sales tracked, active
product appears with `unitsSold: 0`. Omitting them would mean "worst movers"
silently excluded the actual worst — the same class of error as a fixture that
passes because it sees nothing.

`best` does not include them; there is no reason to pad a best-sellers list with
zeroes.

The dashboard shows the top **5**. The contract does not say, and five is what
fits above the fold on a phone; the standalone endpoint is where a longer list
is asked for, via `limit`.

---

## 5. Role gates: reports are owner and manager

`GET` on all four report endpoints requires `owner` or `manager`.

The contract's `UserRole` table lists "reports" under **manager** and nowhere
else; owner has everything. Clerk's three jobs are "record sales, receive
stock, count" and reports are none of them. Viewer is "read only", which grants
a *mode* and says nothing about *scope* — and per CLAUDE.md §13 the narrower
reading wins where the contract is silent.

The narrower reading is not merely the default here, it is the substantive
answer: **every one of these four responses carries cost or margin.**
`grossProfit`, `inventoryValue` (valued at cost), `totalCostValue` and
`grossMarginPct` all disclose what the business pays for its stock and what it
makes on it. That is the figure an owner is least likely to want a part-time
cashier reading, and there is no honest way to serve a redacted version:
`grossProfit` and `inventoryValue` are both in `DashboardSummary`'s `required`
list, so omitting them breaks the contract and every generated client.

`ReportRoleTest` asserts allow **and** deny for all four roles on all four
endpoints — a gate that accidentally permits everyone passes a happy-path test
perfectly (CLAUDE.md §13).

**Consequence for Android, stated so it is not discovered on a device.** The
Home screen's sales tile is now servable, but only to an owner or a manager. A
clerk's Home must not call it — `tabsFor(role)` already exists for exactly this
kind of gating, and CLAUDE.md's own rule is that the point of hiding something
is that nobody is invited into a 403.

**Viewer is the gate I am least sure of** and the one most likely to want
widening: an owner creating a "viewer" for their bookkeeper would expect that
account to read reports. Widening is `hasAnyRole('owner','manager','viewer')`
in four places plus flipping four assertions in `ReportRoleTest`. It is left
narrow because the reverse discovery — a viewer who could read margins all
along — is not fixable after the fact.

---

## 6. What is deliberately not here

- **No caching, no materialised view.** The queries run in single-digit
  milliseconds against 30 weeks of seeded history (plans recorded in
  `docs/MILESTONES.md` M8). A cache would add a staleness question to a report
  whose whole value is being current.
- **No new tables**, so T11's three sweeps are untouched. Every statement here
  is a read; nothing in `reporting/` writes anything, and in particular nothing
  touches `stock_movements` — T5 still has exactly one writer.
- **No `WHERE tenant_id = ?` anywhere.** RLS is the boundary (T2); a second
  clause is the convenience T2 warns about being mistaken for the defence.
