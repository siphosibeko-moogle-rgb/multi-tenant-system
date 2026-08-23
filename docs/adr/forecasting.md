# Forecasting design decisions (pre-M6/M7)

Written before M6 or M7 exist, against the schema M1's baseline migration
already committed to (`supplier_lead_time_observations`, `demand_daily`,
`forecasts`, `forecast_accuracy`, `reorder_recommendations` — all in
`V1__baseline.sql`) and the contract shapes already in `docs/openapi.yaml`
(`Forecast`, `ForecastDetail`, `ReorderRecommendation`,
`SupplierDetail.observedLeadTime`). No code or migration changes here — this
is the record so M6's seed data and M7's implementation don't have to
rediscover these calls, or worse, disagree with each other.

Lives here rather than inline in `MILESTONES.md` because it needs to reason
about *why*, at the length CLAUDE.md's own §9–§16 do, and `MILESTONES.md`'s
own stated format is "things you can actually run" — a formula's derivation
and a threshold's justification aren't runnable. `MILESTONES.md` M6/M7 link
here and carry only the actionable, testable consequences.

---

## 1. Reorder point and safety stock

```
reorder_point = avg_daily_demand × lead_time_days + safety_stock
safety_stock  = z(service_level) × demand_stddev × sqrt(lead_time_days)
```

`z(service_level)` is the standard-normal quantile for the target service
level — `z(0.95) ≈ 1.645`. `service_level` defaults to `0.95`
(`forecasts.service_level` already defaults to `0.950` in the schema).

Both `avg_daily_demand` and `demand_stddev` are computed only over **eligible**
days — see §3. `lead_time_days` comes from §2, not necessarily
`suppliers.lead_time_days`.

**Deliberately excluded: lead-time variability.** The schema carries
`suppliers.lead_time_stddev_days` and `observedLeadTime.stddevDays`, and a
fuller safety-stock formula exists that folds lead-time variance in too
(`z × sqrt(LT·σd² + d²·σLT²)`). Not used here. The brief scopes safety stock
to "demand variability and the service level" specifically, and widening that
silently the first time someone notices the stddev columns sitting unused
would be exactly the kind of undiscussed formula change this document exists
to prevent. If lead-time variance should count too, that's a real
conversation to have before M7, not a fix to slip in during it.

**Not a fixed number, not a percentage of stock on hand.** Restated because
it rules out the easiest wrong implementation: `safety_stock = 20% of
avg_daily_demand × lead_time` looks like a safety margin and isn't one — it
doesn't grow when demand gets erratic, which is the one situation safety
stock exists for.

---

## 2. Which lead time: observed vs promised

**Use the observed average from `supplier_lead_time_observations` when the
supplier has at least 5 recorded observations. Below that, fall back to
`suppliers.lead_time_days`.**

`supplier_lead_time_observations` is keyed on `(tenant_id, supplier_id)` —
per supplier, not per supplier+product — so this is a per-supplier lookup,
matching `SupplierDetail.observedLeadTime` in the contract, which already
carries `sampleSize`, `averageDays`, `stddevDays` for exactly this purpose.

**Why 5, and why that's a different number from M5's "three receipts."**
M5's "Done when" criterion (`observedLeadTime.averageDays reflects actual
ordered→received intervals... after three receipts`) is a plumbing test: it
proves the observation pipeline populates and the average isn't hardcoded to
the form value. Three receipts is enough to demonstrate that.

Trusting that average enough to *set a reorder point from it* is a different,
higher bar, because getting it wrong here costs a real stockout or real
excess stock, not a wrong number on a details screen. At n=3, one unusually
slow delivery is a third of the sample and swings the mean substantially; at
n=5 the same outlier is a fifth. 5 is still reachable within weeks for any
supplier active enough to matter for reordering, and it doesn't block the
fallback path — a rarely-used supplier simply keeps using its promised figure
until it earns a measured one.

**Why the promised figure is the fallback and not the default source.**
Per the brief: promised lead times are optimistic, measured ones aren't.
Preferring the promised number whenever it's available would mean the system
mostly repeats what the supplier said instead of what happened — the
opposite of what `supplier_lead_time_observations` exists for. The fallback
exists only because a brand-new supplier relationship has no observations
yet, not because the promised figure is trusted once alternatives appear.

### There are THREE sources, not two — settled in M7 step 3

This section originally described two, and the schema has a third:
`product_suppliers.lead_time_days`, commented in `V1` as "overrides supplier
default". A per-product promise, for the real case of a supplier who ships
most lines in a week and one particular line in a month.

**Resolved ranking, highest first:**

| Rank | Source | Kind |
|---|---|---|
| 1 | `avg(supplier_lead_time_observations.lead_time_days)`, at n ≥ 5 | measured |
| 2 | `product_suppliers.lead_time_days`, when set | promised, per product |
| 3 | `suppliers.lead_time_days` | promised, general |

**The override refines the *promised* tier; it does not compete with the
measured one.** That ordering is the whole decision, and the alternative is
genuinely tempting — "overrides supplier default" reads like it should
override everything. It must not. Ranking a hand-typed per-product number
above real observations inverts this section's core principle: the system
would go back to repeating what somebody once typed instead of what the
supplier actually did, and it would do so most often on exactly the products
somebody cared enough about to type a number for. A stale override would
silently outrank a hundred measured deliveries.

Note also that the measured figure is **necessarily per-supplier**:
`supplier_lead_time_observations` is keyed on `(tenant_id, supplier_id)` with
no product column, so a per-product measured average does not exist to be
preferred even in principle. Ranks 2 and 3 are the only place per-product
granularity can enter, and both are promises.

**Which supplier, when a product has several.** `product_suppliers` allows
many rows per product. The preferred one wins (`is_preferred`), then the
oldest link. Deterministic on purpose — a reorder point that changed between
runs because two suppliers tied would be indistinguishable from a real
change in demand.

**A product with no `product_suppliers` row has no lead time and therefore no
reorder point.** Null, not a default. See §1's implementation note in
`Forecaster`: substituting a plausible default produces a made-up reorder
point indistinguishable from a real one.

---

## 3. Censored demand: the stockout exclusion, and the spiral it prevents

`demand_daily.had_stockout = true` means `units_sold` on that row is a floor,
not a count — the shop could have sold more if there had been stock to sell.

**Rule: days where `had_stockout` is true are excluded entirely from both the
sum and the count when computing `avg_daily_demand` and `demand_stddev`.**
Not zeroed, not imputed to some estimated "true" demand — just removed from
the eligible set, the same way a `NULL` is excluded from `AVG()` rather than
counted as `0`. Imputation invites its own bias and isn't what's asked for
here; exclusion is the smallest correct thing.

**The failure mode, spelled out, because a system that gets this wrong makes
stockouts *worse* over time, not better:**

1. A product stocks out. The day's `units_sold` is genuinely lower than
demand — it's capped by what was on the shelf, not by what customers wanted.
2. If that day is averaged in anyway, `avg_daily_demand` drops.
3. `reorder_point = avg_daily_demand × lead_time + safety_stock` drops with
it — a lower average means a lower reorder point.
4. A lower reorder point means the next order fires later and for less. The
buffer that was supposed to absorb the *next* lead time is now thinner than
the real demand justifies.
5. The next stockout arrives sooner, and it's included in the average too.

Left uncorrected this is self-reinforcing: the system's own output degrades
the input it's handed next cycle, and a product that's actually a strong,
consistently-sold-out seller gets read as a declining one. This is exactly
the shape M6's stockout-period fixture (§ below, and `MILESTONES.md` M6)
needs to exist to catch — a seed set with no stockout days can't distinguish
"excludes correctly" from "happens to average correctly by luck."

**What counts as "eligible" elsewhere in this document.** §5's readiness
count (non-zero demand days) is over eligible days only. §5's calendar-span
floor (history length) is **not** narrowed the same way — see §5 for why.

**This document is silent on how a return nets out of `units_sold`, and that
silence was mistaken for coverage once already.** The rule was decided in M7
step 1 and is recorded on the column itself — `COMMENT ON COLUMN
demand_daily.units_sold`, added by `V9` — rather than only here, because the
person who needs it is reading the table. In short: `units_sold` is `sale`
movements minus `sale_return` movements, floored at zero per day. A unit
sold and then handed back and put on the shelf was never real demand, and
counting it would overstate `avg_daily_demand` straight into §1's reorder
point. A damaged-goods return posts no ledger movement at all (`V5`), so it
is correctly counted as demand — the customer wanted it, the shelf lost it.
Nothing about this changes §3's had-stockout exclusion, which is a separate
question answered by a separate column.

---

## 4. Method selection: `MethodSelector`, not configuration

Three buckets, decided from the shape of a product/location's own
`demand_daily` data, never from a config flag:

| Bucket | Condition | Method |
|---|---|---|
| Too-new | Fails the §5 readiness threshold | `insufficient_data` |
| Intermittent | Passes readiness; `nonzero_fraction < 0.3` | `croston` |
| Steady | Passes readiness; `nonzero_fraction >= 0.3` | `moving_average` / `weighted_moving_average` / `exponential_smoothing` |

`nonzero_fraction` = (eligible days with `units_sold > 0`) ÷ (total eligible
days), over the trailing history window, where "eligible" is the same
had-stockout exclusion as §3.

**The trailing window is twelve months** (`app.forecasting.history-window-days`,
default 365), settled in M7 step 2 — this document had left the length
unstated. Long enough to contain a full annual cycle if the product has one,
short enough that demand from over a year ago stops steering today's reorder
point; an unbounded window fails that second half, since a product whose market
genuinely changed would keep being forecast from the old one. It is a
configuration property rather than a constant so it can be retuned without a
redeploy, and it is still per-*deployment*: a shop selling fresh produce and one
selling furniture want different windows, and serving both from one instance
needs the value to come from a tenant lookup —
`DemandSeriesRepository.windowDays()` is the single place that would change.
Values below §5's 42-day readiness floor are rejected at startup, because a
window shorter than the floor would refuse every product a forecast, silently
and forever.

**Why 0.3, and why it doesn't need to be exact.** M6's two calibrating shapes
(§ below) are a steady seller at ~18.6/week (measured; see §8 item 1) —
which sells close to every day,
`nonzero_fraction ≈ 1.0` — and an intermittent product selling roughly 1 day
in 10, `nonzero_fraction ≈ 0.1`. Both sit far from 0.3 on either side. The
line only has to fall somewhere in that gap; it doesn't have to be
theoretically precise (a fuller treatment would classify on both frequency
*and* the coefficient of variation of demand size — not needed to separate
the shapes this system actually seeds).

**Left open, deliberately: which of the three "steady" methods, and how the
seasonal/trending shape (M6 §2) picks between them.** Fixing that now, without
an implementation to test it against, risks pinning a rule nobody has
validated. What's fixed is the top-level split (too-new / intermittent /
steady) and the requirement that M7 make this choice from measured shape —
trend/seasonality detection, not a silent default to plain
`moving_average` that would flatly under- or over-forecast a trending
product every single period.

> **RESOLVED in M7 step 2, with an implementation and real data to test it
> against — which is the condition this was waiting on.**
>
> `MethodSelector` measures `relativeTrend`: an ordinary least-squares slope
> over the eligible days, multiplied by the window length and divided by the
> mean, so it reads as "the trend line rises by this much of a mean level
> across the window". Relative rather than raw, so one threshold serves every
> product — a slope of 0.01/day is a strong trend at 0.2/day and noise at
> 30/day.
>
> | `|relativeTrend|` | Method |
> |---|---|
> | ≤ 0.5 | `moving_average` |
> | > 0.5 | `weighted_moving_average` |
>
> Absolute value, so a collapsing product is caught as well as a growing one.
> A flat mean that keeps quoting last quarter's rate for a dying product
> recommends reorders nobody will sell, and that is the more urgent of the two.
>
> **Calibration, measured over five RNG seeds of M6's 30-week data** (see
> `ForecastRoutingSeedDataTest`, which prints the matrix):
>
> | shape | observed `relativeTrend` range |
> |---|---|
> | steady | −0.158 … +0.113 |
> | stockout | −0.376 … −0.115 |
> | trending | +1.498 … +1.712 |
>
> The gap runs from 0.376 to 1.498 and 0.5 sits inside it. **Low in the gap
> rather than centred, on purpose**, because the two errors are not
> symmetric: on a flat series the weighted average and the plain mean agree
> to within 0.001 (`MethodSelectorTest
> .misroutingAFlatSeriesToTheWeightedAverageIsCheap`), so a false positive
> costs almost nothing — while a ramp routed to a flat mean is precisely the
> failure this paragraph forbids. When the costs are lopsided the line
> belongs on the cheap side. It also leaves room for real products that
> trend less dramatically than M6's synthetic one, which quadruples its rate
> by construction.
>
> The stockout shape's −0.376 is the closest non-trending case, and it is a
> genuine level shift rather than noise: M6 sells that product harder before
> its outage than after its restock, and with the censored days removed the
> two regimes sit next to each other.
>
> **`exponential_smoothing` stays unselected.** For a flat series it is a
> slower `moving_average`; for a trending one, *single* exponential smoothing
> lags a ramp the same way a plain mean does — it needs a trend term (Holt) to
> compete with the weighted average, and adding Holt is a modelling decision,
> not a wiring one. Same reasoning as `ml_model` below: an enum slot existing
> is not a decision that it should be filled.
>
> **Known limitation, recorded rather than hidden:** a linear weighting over a
> long window still lags a ramp — for a straight ramp from a to b it lands
> near a + ⅔(b−a) rather than at b. It is strictly better than the plain mean
> it replaces (a + ½(b−a)), and closing the rest of the gap is what a Holt
> model would be for.
>
> **Seasonality is an OPEN REQUIREMENT, not a recorded limitation.** The trend
> half of "trend/seasonality detection" is done; the seasonal half is not, and
> M6 seeds a shape it calls "seasonal or trending" that is implemented purely
> as a trend. No method here models a cycle, so a genuinely seasonal product
> gets its peaks and troughs averaged flat — a reorder point that is right for
> an average week of the year and wrong, in both directions at different times,
> exactly when it matters.
>
> **This must be resolved before M7 is called complete.** It is not a permanent
> asterisk and must not become one. Until it is, the gap is *gated* rather than
> merely noted:
>
> - `DemandSeries.seasonalityIndicator()` measures **detrended** autocorrelation
> at candidate cycle lengths (7, 14, 30, 91, 182, 365 days), reporting how
> strongly the part of the series the trend does *not* explain repeats itself.
> Detrending first is what makes it mean anything — raw autocorrelation is high
> at every lag for any trending series, so without it every growing product
> would look seasonal.
> - Above `MethodSelector.SEASONALITY_THRESHOLD` (0.35),
> `Selection.isSeasonalitySuspected()` is set and the forecast's reorder point
> carries an explicit caveat **in the API response**, not only here.
> - It detects *that* a cycle exists, never which one. Detecting seasonality is
> a much cheaper problem than forecasting it, and the honest thing to do with
> the gap between them is say so to the person acting on the number.
>
> Calibration, measured across five seeds of M6's data: every seeded shape lands
> between 0.026 and 0.189, and a constructed weekly cycle lands far above 0.35.
> Deliberately **not** the textbook 2/√n significance line (~0.14 at 212 days) —
> ordinary noise crosses that often enough that the caveat would appear on
> products that are not seasonal at all, and a warning that shows up everywhere
> is one nobody reads.

**`ml_model` stays unused.** `forecast_method` already has an `ml_model`
value in the applied `V1` enum — future-proofing, not a green light. Per §7,
it doesn't get used until the naive-baseline comparison shows the simpler
methods losing by a margin that justifies the complexity. An enum slot
existing is not a decision that it should be filled.

---

## 5. Readiness threshold

**Both of the following must hold, or the forecast is `insufficient_data`,
`projectedStockoutOn` is `null`, `reorder_point` is `null`, and the UI shows
"still learning":**

- **`history_days >= 42`** (6 calendar weeks) — measured as the calendar
span from the first `demand_daily` row to the most recent for that
product/location, **including** had-stockout and zero-demand days.
- **at least 10 eligible non-zero-demand days** (`units_sold > 0`,
`had_stockout = false`) within that window.

**Why history is a calendar span, not a row count, and why it isn't narrowed
by the §3 exclusion the way the non-zero count is.** The 42-day floor exists
to guarantee the window has actually crossed six instances of every weekday
— enough to average out one anomalous week (a promotion, a public holiday)
without waiting a full quarter. A had-stockout day still tells you six weeks
of *calendar time* have genuinely passed; it just isn't trustworthy evidence
of *demand*. Excluding it from the demand average (§3) and excluding it from
the clock that decides whether there's been enough time to trust an average
are different questions, and conflating them would make a stockout-prone
product's readiness clock run slower for no good reason — on top of the
average it already denies that product, which is punishment enough.

**Why 10 non-zero days, not fewer.** Below that, both the average and
especially the spread are dominated by one or two individual sales — the
Croston math in particular needs enough transactions to reflect a rate
rather than a handful of one-offs. 10 is small enough not to indefinitely
punish a genuinely slow mover, but large enough that a product with 2–3
sales in six weeks (which M6's dead-stock and dead-adjacent shapes will
produce) correctly stays `insufficient_data` rather than getting a number
built from 2 data points dressed up as a rate.

**Why AND, and why there's no upper cap.** Being conservative here was
stated as the explicit preference — say nothing rather than be confidently
wrong once. A product can rack up 10 non-zero days in under two weeks and
still wait for week 6, because ten sales in twelve days says nothing about
whether week 3's Tuesday looks like week 1's. There's no cap on the other
side either: a dead-stock product (M6 §2, "no sales in months") will likely
never cross 10 non-zero days, and stays `insufficient_data` indefinitely.
That's correct, not a gap to close — a product with no measurable demand has
no reorder signal to report, and inventing one would be exactly the
confidently-wrong number this threshold exists to prevent.

**Checked against every M6 shape (§ below), so the thresholds don't surprise
M7 later:**

| M6 shape | Crosses the bar at roughly | Note |
|---|---|---|
| Steady, ~18.6/week | Week 6 (day-count floor, not the non-zero floor — it clears 10 non-zero days by ~day 4) | The two conditions are independent; a very active product is still gated by the calendar floor. |
| Stockout period | Same as steady, once outside the outage | Outage days are already excluded from "eligible," so they can't accidentally count toward readiness either. |
| Intermittent, ~1/10 days | ~Week 14 (100 calendar days to accumulate 10 non-zero days at that rate) | Genuinely slower than the steady seller, on purpose. M6 must seed enough calendar time (it already commits to 6–12 months) for this shape to clear the bar at all, not just for the steady one. |
| Seasonal / trending | Same as steady, assuming regular sales | — |
| Dead stock | Never | Stays `insufficient_data` forever — correct, see above. |
| Brand-new, <2 weeks | Never (yet) | Fails the 42-day floor immediately; unambiguous. |

---

## 6. The explanation string

`ForecastDetail.explanation` is already `required` in the contract
unconditionally — not conditioned on `method`. That has a consequence worth
stating plainly: **the `insufficient_data` path needs a real sentence too**,
not an empty string waiting for the confident-path branch to be written
first. Something like *"Still learning this product — 12 days of sales
recorded so far, needs about 30 more before a number is trustworthy."*

For a ready forecast, the template is the one already in the brief and
matches the contract's own example (`'You sell about 7.5 phones a week. 17
in stock covers roughly 16 days.'`):

> "You sell about **{avg_daily_demand × 7, friendly-rounded}** a week.
> **{quantity_on_hand}** in stock covers roughly **{days_of_cover}** days,
> and your supplier takes **{lead_time_days from §2}**."

It should be truthful about which lead time it's quoting — the observed
figure and the promised one can disagree, and the sentence is the one place
a shop owner ever sees the number, so it can't silently mix the two.

---

## 7. `forecast_accuracy` versus the naive baseline

**Populated from day one, and scored against "same as last period" from day
one — not added once the real model looks good enough to survive the
comparison.**

"Same as last period," precisely: for a forecast evaluated over
`[period_start, period_end)`, the naive baseline's prediction is the actual
`units_sold` total from the immediately preceding period of equal length.
No averaging, no trend, no seasonality — it's deliberately the dumbest
possible forecast, which is the point: if the real method can't beat it,
"the model isn't earning its complexity" is a mechanical fact, not a
judgment call.

**Representation was left to M7's migration author. RESOLVED in M7 step 1 —
shape (a).** Two shapes were considered:

- (a) generate a synthetic forecast row per evaluation using a new
`naive` value on `forecast_method`, and score it through the existing
`forecast_accuracy` table exactly like any real method. One code path, no
new columns.
- (b) add sibling columns (`naive_predicted_qty`, `naive_abs_pct_error`)
directly on `forecast_accuracy`.

**(a) was chosen, and `V9__demand_rollup_and_naive_baseline.sql` adds the
`naive` value to the `forecast_method` enum.** One scoring path rather than
two, and no columns sitting NULL on every non-baseline row.

**A new enum value rather than reusing `moving_average`** — worth stating,
because the two compute the same number for a one-period window and reusing
the existing value looks like a harmless simplification. It isn't: the
comparison stops meaning anything the moment both sides carry the same
label. The question this evaluation exists to answer is "is the chosen
method beating the dumbest possible forecast", and if both sides are stored
as `moving_average` no query can separate them. The per-bucket finding below
becomes unreadable the same way.

The constraint that was already settled still stands: the comparison must
exist from day one, and it must be visible per demand-shape bucket (steady
vs. intermittent vs. seasonal), not only as one averaged number — a method
that beats naive for steady sellers and loses for intermittent ones is a
real finding, and averaging the two together would hide exactly the thing
this evaluation exists to surface.

---

## 8. Contradictions and gaps found while writing this

Per CLAUDE.md §1's rule — flagged, not silently resolved:

1. **M6's steady-seller figure changes.** The current `MILESTONES.md` M6 text
says *"steady seller (~7–8/week, low variance) — the worked example from
the brief"*; this task's instructions (and CLAUDE.md's own explanation
example, *"You sell about 20 a week..."*) specify **20/week**. These are two
different numbers for the thing both call "the worked example." I don't have
visibility into whatever "the brief" originally meant by 7–8/week — I've
updated `MILESTONES.md` to 20/week because that's what was explicitly
specified here, but the discrepancy should be noted rather than assumed to
be a correction.

> **This figure has now drifted twice, and the second time it was measured.**
> M7 step 1 rolled `stock_movements` up into `demand_daily` against the real
> 30-week generator and got **2.65/day (~18.6/week)** — 562 units over 212
> days — for the shape everything above calls "20/week". That is the seeder
> behaving exactly as coded (~87% of days × 2–4 units ≈ 2.7/day), and
> `DemandRollupSeedDataTest.theRollupReconcilesWithTheLedger` proves the
> rollup is faithful to the ledger, so the gap is in the prose rather than in
> the data or the code.
>
> **`MILESTONES.md` was corrected to 2.65/day; `TenantSeeder` was
> deliberately left alone.** Changing working, verified seed data so it
> agrees with a number written before anything measured it is backwards — it
> would discard the one figure here that came from an observation rather than
> from an assumption.
>
> So: **7–8/week → 20/week → 18.6/week measured.** If a future reader finds
> the code and the prose disagreeing about this number again, the code is the
> one that has been checked. Do not "correct" 2.65 back to 20 on the strength
> of the older sentences above — they are kept only so the history is legible.
2. **M7's existing "Done when" bullet inherits the old number.** *"The
steady seller reports ~1.1/day"* is `7–8/week ÷ 7`. Updated in lockstep to
`~2.9/day` (`20/week ÷ 7`) so M6 and M7 don't quote two different figures for
the same fixture — see item 1, same root cause.
3. **`forecast_method` already has `ml_model`** in the applied `V1`
migration, ahead of "no ML until this says otherwise." Not a contradiction —
an unused enum value is harmless, and `T9` means it can't be removed without
a new migration anyway — but worth recording so its presence isn't later
read as prior sign-off to use it. See §4/§7.
4. **The existing M7 "Done when" bullet about a 21-day vs. 3-day supplier
lead time doesn't say which figure it means.** Under §2, promised and
observed can differ, and the milestone should test the case that actually
matters — the observed one, when there are enough samples, and the fallback
otherwise. Tightened in `MILESTONES.md` rather than left ambiguous.

Nothing else in the current M6/M7 text conflicts with the decisions above —
the censored-demand and readiness-threshold asks were already anticipated in
outline (*"verify this explicitly, it is the single most common forecasting
bug"*, *"Days with a recorded stockout do not drag average demand down"*);
this document supplies the exact numbers and formulas that were missing, not
a correction to something that was wrong.
