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

**Why 0.3, and why it doesn't need to be exact.** M6's two calibrating shapes
(§ below) are a steady seller at ~20/week — which sells close to every day,
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
| Steady, 20/week | Week 6 (day-count floor, not the non-zero floor — it clears 10 non-zero days by ~day 4) | The two conditions are independent; a very active product is still gated by the calendar floor. |
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

**Representation is an M7 migration decision, not a forecasting-design one,
and isn't made here** — the current `forecast_accuracy` table has no column
for a baseline figure and `forecast_method` has no `naive` value. Two shapes
were considered, sketched for whoever writes M7's migration:

- (a) generate a synthetic forecast row per evaluation using a new
`naive` value on `forecast_method`, and score it through the existing
`forecast_accuracy` table exactly like any real method. One code path, no
new columns.
- (b) add sibling columns (`naive_predicted_qty`, `naive_abs_pct_error`)
directly on `forecast_accuracy`.

(a) is the lighter touch and is the recommendation, but the call belongs to
whoever writes that migration, with the constraint above already settled:
the comparison must exist from day one, and it must be visible per
demand-shape bucket (steady vs. intermittent vs. seasonal), not only as one
averaged number — a method that beats naive for steady sellers and loses for
intermittent ones is a real finding, and averaging the two together would
hide exactly the thing this evaluation exists to surface.

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
