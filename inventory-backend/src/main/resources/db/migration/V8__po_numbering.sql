-- M5: human-facing purchase-order numbers, same reasoning as V7's sale
-- numbering. A single counter shared by every tenant (or one Postgres
-- SEQUENCE per tenant) leaks purchasing volume the same way V7 rejected for
-- sales: the gap between two of a tenant's PO numbers estimates how much
-- that tenant orders. Per-tenant numbering, allocated the same
-- UPDATE ... RETURNING way as next_sale_number, contains that to the tenant
-- that already owns the number.
ALTER TABLE tenants
    ADD COLUMN next_po_number bigint NOT NULL DEFAULT 1 CHECK (next_po_number > 0);
