-- M7 step 6. reorder_recommendations.urgency.
--
-- A contract/schema gap, surfaced rather than worked around (CLAUDE.md §1).
-- docs/openapi.yaml lists `urgency` in ReorderRecommendation's `required` array
-- with enum [critical, high, normal], and V1's table has no column for it.
--
-- The alternative was deriving it on read from quantity_on_hand against
-- reorder_point. Rejected: urgency is measured against the SUPPLIER'S LEAD TIME
-- (see ReorderService — "urgent" means "you run out before an order can land",
-- which a three-day supplier and a three-week one make true at very different
-- shelf levels), so deriving it later would mean re-resolving the lead time on
-- every read and could silently disagree with the rationale text stored beside
-- it. The recommendation and its urgency were decided at the same moment from
-- the same numbers; they are stored together.
CREATE TYPE recommendation_urgency AS ENUM ('critical', 'high', 'normal');

ALTER TABLE reorder_recommendations
    ADD COLUMN urgency recommendation_urgency NOT NULL DEFAULT 'normal';

COMMENT ON COLUMN reorder_recommendations.urgency IS
$$How soon this needs acting on, judged against the supplier's lead time rather
than a fixed number of days.

critical – already out of stock, or projected to run out before an order placed
           today could arrive
high     – less than two lead times of cover left
normal   – at or below the reorder point, with room to order calmly

The lead time is what makes this meaningful: the same shelf level is an
emergency with a three-week supplier and a routine top-up with a three-day one.
Stored rather than derived on read because it was decided together with the
recommended quantity and the rationale text, from one set of numbers.$$;
