-- M4: human-facing receipt numbers.
--
-- A global sequence (one counter shared by every tenant) was rejected on
-- sight: sale_number is shown to the customer on a receipt, and a shared
-- counter lets anyone who has ever seen two of a tenant's receipts estimate
-- that tenant's total sales volume from the gap between the numbers — and
-- lets a competitor watching their OWN receipt numbers estimate everyone
-- else's. Per-tenant numbering leaks nothing outside the tenant that already
-- owns the number.
--
-- The counter lives on `tenants` rather than a new table because a new table
-- would need its own RLS policy, and its own entries in T11's three sweeps
-- (TenantIsolationTest / SchemaSmokeTest / LoginRoleTest), to guard exactly
-- the same fact `tenants` already guards for free: `tenants.tenant_self`
-- already restricts every row to `id = current_tenant_id()`, so a second
-- tenant's counter is already invisible and already unwritable through it.
ALTER TABLE tenants
    ADD COLUMN next_sale_number bigint NOT NULL DEFAULT 1 CHECK (next_sale_number > 0);

COMMENT ON COLUMN tenants.next_sale_number IS
    'Next receipt number to assign for this tenant. Allocated by '
    'SaleService via UPDATE ... RETURNING, which takes the row''s lock for '
    'the duration of the assignment — the same reasoning as T12: the lock '
    'is what adjudicates two sales racing for a number, not a read-then-write '
    'check beforehand. Concurrent sales for DIFFERENT tenants do not '
    'contend, because each tenant has its own row and therefore its own lock.';
