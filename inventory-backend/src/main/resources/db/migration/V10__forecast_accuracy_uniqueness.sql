-- M7 step 5. One score per forecast per evaluation period.
--
-- The accuracy job runs repeatedly and must be safe to run repeatedly. Without
-- a constraint that is a convention held in application code: the job checks
-- whether a row exists and then inserts one, which is a read-then-write race,
-- and two concurrent runs (a scheduled one and a manual recompute, say) both
-- see nothing and both insert.
--
-- The failure is quiet in the worst way. Duplicate scores do not error and do
-- not look wrong in any single row — they silently reweight every aggregate
-- built on top, so a method that happened to be scored twice on its good
-- periods reports a better MAPE than it earned. The whole point of
-- docs/adr/forecasting.md §7 is that the naive comparison is a mechanical fact
-- rather than a judgment call, and a duplicate-sensitive average is not a
-- mechanical fact.
--
-- period_start alone is enough alongside forecast_id: a forecast has one
-- horizon, so its evaluation window is determined by where it starts.
CREATE UNIQUE INDEX forecast_accuracy_period_uq
    ON forecast_accuracy (tenant_id, forecast_id, period_start);

COMMENT ON TABLE forecast_accuracy IS
$$Scores a past forecast against what actually happened over its horizon, and —
per docs/adr/forecasting.md §7 — against the naive "same as last period"
baseline from day one, not once a real model looks good enough to survive the
comparison.

The baseline is represented as shape (a) from §7: a synthetic forecasts row
carrying forecast_method = 'naive' (added in V9), scored through this table
exactly like any real method. One scoring path, no columns sitting NULL on
every non-baseline row.

abs_pct_error is a PERCENTAGE, and is NULL when actual_qty is zero. MAPE is
undefined against a zero actual — the error is infinite however small the
prediction — and storing a large finite number instead would quietly dominate
every average it entered. Intermittent products have many zero periods, so this
is the common case rather than the edge case, and it is exactly the bucket §7
cares most about comparing.$$;
