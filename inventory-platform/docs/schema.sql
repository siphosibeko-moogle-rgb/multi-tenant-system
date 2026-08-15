-- =====================================================================
-- Multi-tenant Inventory Management System — baseline schema
-- Target: PostgreSQL 14+   (Flyway: V1__baseline.sql)
--
-- Design decisions:
--   * Shared-schema multi-tenancy: every business row carries tenant_id.
--   * Row-Level Security is the enforcement boundary, not application
--     WHERE clauses. The API sets `app.tenant_id` per request/transaction
--     from the authenticated JWT claim.
--   * Composite foreign keys (tenant_id, id) make a cross-tenant
--     reference physically impossible, even with a buggy service layer.
--   * Stock is an append-only ledger (stock_movements). product_stock is
--     a maintained cache, never the source of truth.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive emails

-- ---------------------------------------------------------------------
-- 0. Tenant context helpers
-- ---------------------------------------------------------------------

-- Returns the tenant scope of the current DB session.
-- The API must issue:  SET LOCAL app.tenant_id = '<uuid>';
-- at the start of every transaction (a Spring TransactionSynchronization
-- or a Hibernate StatementInspector is the usual hook).
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid
$$;

-- Optional: the acting user, used to stamp audit rows.
CREATE OR REPLACE FUNCTION current_app_user_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only; post a compensating row instead',
        TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$;

-- ---------------------------------------------------------------------
-- 1. Enumerated types
-- ---------------------------------------------------------------------

CREATE TYPE tenant_status  AS ENUM ('trial', 'active', 'suspended', 'closed');
CREATE TYPE user_role      AS ENUM ('owner', 'manager', 'clerk', 'viewer');
CREATE TYPE user_status    AS ENUM ('invited', 'active', 'disabled');

CREATE TYPE movement_type  AS ENUM (
    'purchase_receipt',   -- stock in  from a supplier
    'sale',               -- stock out to a customer
    'sale_return',        -- stock in  from a customer
    'purchase_return',    -- stock out back to a supplier
    'adjustment',         -- manual correction (damage, theft, recount)
    'stocktake',          -- delta produced by a physical count
    'transfer_in',
    'transfer_out',
    'opening_balance'
);

CREATE TYPE sale_status     AS ENUM ('draft', 'completed', 'voided', 'refunded');
CREATE TYPE po_status       AS ENUM ('draft', 'ordered', 'partial', 'received', 'cancelled');
CREATE TYPE forecast_method AS ENUM ('moving_average', 'weighted_moving_average',
                                     'exponential_smoothing', 'croston', 'ml_model', 'insufficient_data');
CREATE TYPE recommendation_status AS ENUM ('open', 'ordered', 'dismissed', 'expired');

-- ---------------------------------------------------------------------
-- 2. Tenants and identity
-- ---------------------------------------------------------------------

CREATE TABLE tenants (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            citext NOT NULL UNIQUE,          -- login hint / subdomain
    name            text   NOT NULL,
    status          tenant_status NOT NULL DEFAULT 'trial',
    country_code    char(2),
    currency_code   char(3) NOT NULL DEFAULT 'ZAR',
    timezone        text    NOT NULL DEFAULT 'UTC',  -- daily rollups use this
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenants_slug_len CHECK (length(slug) BETWEEN 2 AND 63)
);
CREATE TRIGGER trg_tenants_updated BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email           citext NOT NULL,
    password_hash   text,                            -- null while status='invited'
    full_name       text NOT NULL,
    role            user_role   NOT NULL DEFAULT 'clerk',
    status          user_status NOT NULL DEFAULT 'invited',
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    UNIQUE (tenant_id, id)                           -- FK target for child tables
);
-- Same human may hold accounts in two tenants, so email is unique per tenant.
-- Login therefore needs a tenant hint (slug) or an email->tenant lookup step.
CREATE UNIQUE INDEX users_tenant_email_uq ON users (tenant_id, email)
    WHERE deleted_at IS NULL;
CREATE TRIGGER trg_users_updated BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE refresh_tokens (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL,
    user_id         uuid NOT NULL,
    token_hash      text NOT NULL UNIQUE,            -- store the hash, never the token
    device_label    text,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (tenant_id, user_id)
    WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------
-- 3. Catalogue: locations, categories, suppliers, products
-- ---------------------------------------------------------------------

CREATE TABLE locations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            text NOT NULL,
    address         text,
    is_default      boolean NOT NULL DEFAULT false,
    is_active       boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);
CREATE UNIQUE INDEX locations_one_default ON locations (tenant_id)
    WHERE is_default;
CREATE TRIGGER trg_locations_updated BEFORE UPDATE ON locations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE categories (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    parent_id       uuid,
    name            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES categories (tenant_id, id) ON DELETE SET NULL
);
CREATE TRIGGER trg_categories_updated BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE suppliers (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            text NOT NULL,
    contact_name    text,
    email           citext,
    phone           text,
    address         text,
    -- Forecasting needs these: reorder point = demand x lead time + safety stock
    lead_time_days      integer NOT NULL DEFAULT 7 CHECK (lead_time_days BETWEEN 0 AND 365),
    lead_time_stddev_days numeric(6,2) NOT NULL DEFAULT 0 CHECK (lead_time_stddev_days >= 0),
    min_order_value numeric(14,2) CHECK (min_order_value IS NULL OR min_order_value >= 0),
    currency_code   char(3),
    is_active       boolean NOT NULL DEFAULT true,
    notes           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX suppliers_tenant_name_uq ON suppliers (tenant_id, lower(name))
    WHERE deleted_at IS NULL;
CREATE TRIGGER trg_suppliers_updated BEFORE UPDATE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE products (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sku                 text NOT NULL,
    barcode             text,
    name                text NOT NULL,
    description         text,
    category_id         uuid,
    unit_of_measure     text NOT NULL DEFAULT 'each',
    cost_price          numeric(14,4) NOT NULL DEFAULT 0 CHECK (cost_price >= 0),
    selling_price       numeric(14,4) NOT NULL DEFAULT 0 CHECK (selling_price >= 0),
    tax_rate            numeric(6,4)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate < 1),
    -- Manual overrides; when null the forecaster supplies the value.
    reorder_point       numeric(14,3) CHECK (reorder_point IS NULL OR reorder_point >= 0),
    reorder_quantity    numeric(14,3) CHECK (reorder_quantity IS NULL OR reorder_quantity > 0),
    safety_stock        numeric(14,3) CHECK (safety_stock IS NULL OR safety_stock >= 0),
    is_tracked          boolean NOT NULL DEFAULT true,   -- false for services
    is_active           boolean NOT NULL DEFAULT true,
    allow_negative_stock boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES categories (tenant_id, id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX products_tenant_sku_uq ON products (tenant_id, upper(sku))
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX products_tenant_barcode_uq ON products (tenant_id, barcode)
    WHERE barcode IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX products_tenant_name_idx ON products (tenant_id, lower(name));
CREATE INDEX products_tenant_category_idx ON products (tenant_id, category_id);
CREATE TRIGGER trg_products_updated BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Many-to-many: who can supply this product, at what cost and lead time.
CREATE TABLE product_suppliers (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL,
    product_id          uuid NOT NULL,
    supplier_id         uuid NOT NULL,
    supplier_sku        text,
    unit_cost           numeric(14,4) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    pack_size           numeric(14,3) NOT NULL DEFAULT 1 CHECK (pack_size > 0),
    lead_time_days      integer CHECK (lead_time_days IS NULL OR lead_time_days >= 0), -- overrides supplier default
    is_preferred        boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, product_id, supplier_id),
    FOREIGN KEY (tenant_id, product_id)  REFERENCES products  (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX product_suppliers_one_preferred
    ON product_suppliers (tenant_id, product_id) WHERE is_preferred;
CREATE TRIGGER trg_product_suppliers_updated BEFORE UPDATE ON product_suppliers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- 4. Inventory: append-only ledger + cached balances
-- ---------------------------------------------------------------------

CREATE TABLE stock_movements (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid NOT NULL,
    product_id      uuid NOT NULL,
    location_id     uuid NOT NULL,
    movement_type   movement_type NOT NULL,
    -- Signed: positive = stock in, negative = stock out. Never zero.
    quantity_delta  numeric(14,3) NOT NULL CHECK (quantity_delta <> 0),
    unit_cost       numeric(14,4) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    -- Soft link to whatever caused this row (sale, purchase order, stocktake).
    reference_type  text,
    reference_id    uuid,
    reason          text,
    occurred_at     timestamptz NOT NULL DEFAULT now(),  -- business time (may be backdated)
    created_at      timestamptz NOT NULL DEFAULT now(),  -- system time
    created_by      uuid,
    FOREIGN KEY (tenant_id, product_id)  REFERENCES products  (tenant_id, id),
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by)  REFERENCES users     (tenant_id, id),
    CONSTRAINT stock_movements_sign_matches_type CHECK (
        (movement_type IN ('purchase_receipt','sale_return','transfer_in') AND quantity_delta > 0)
     OR (movement_type IN ('sale','purchase_return','transfer_out')        AND quantity_delta < 0)
     OR (movement_type IN ('adjustment','stocktake','opening_balance'))     -- either direction
    )
);
CREATE INDEX stock_movements_product_time_idx
    ON stock_movements (tenant_id, product_id, occurred_at DESC);
CREATE INDEX stock_movements_reference_idx
    ON stock_movements (tenant_id, reference_type, reference_id);
CREATE INDEX stock_movements_occurred_idx
    ON stock_movements (tenant_id, occurred_at DESC);

-- The ledger is immutable: corrections are new rows, never edits.
CREATE TRIGGER trg_stock_movements_immutable
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Cached balance per product per location. Derived data — it can always be
-- rebuilt with: SELECT sum(quantity_delta) ... GROUP BY product_id, location_id.
CREATE TABLE product_stock (
    tenant_id           uuid NOT NULL,
    product_id          uuid NOT NULL,
    location_id         uuid NOT NULL,
    quantity_on_hand    numeric(14,3) NOT NULL DEFAULT 0,
    quantity_reserved   numeric(14,3) NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
    quantity_available  numeric(14,3) GENERATED ALWAYS AS (quantity_on_hand - quantity_reserved) STORED,
    version             bigint NOT NULL DEFAULT 0,       -- JPA @Version / optimistic locking
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, product_id, location_id),
    FOREIGN KEY (tenant_id, product_id)  REFERENCES products  (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX product_stock_location_idx ON product_stock (tenant_id, location_id);

-- Applies every ledger row to the cached balance, and enforces the
-- oversell rule unless the product explicitly permits negative stock.
CREATE OR REPLACE FUNCTION apply_stock_movement() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    new_qty        numeric(14,3);
    allows_negative boolean;
BEGIN
    INSERT INTO product_stock (tenant_id, product_id, location_id, quantity_on_hand)
    VALUES (NEW.tenant_id, NEW.product_id, NEW.location_id, NEW.quantity_delta)
    ON CONFLICT (tenant_id, product_id, location_id) DO UPDATE
        SET quantity_on_hand = product_stock.quantity_on_hand + EXCLUDED.quantity_on_hand,
            version          = product_stock.version + 1,
            updated_at       = now()
    RETURNING quantity_on_hand INTO new_qty;

    IF new_qty < 0 THEN
        SELECT p.allow_negative_stock INTO allows_negative
        FROM products p
        WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.product_id;

        IF NOT COALESCE(allows_negative, false) THEN
            RAISE EXCEPTION 'Insufficient stock for product % at location % (result would be %)',
                NEW.product_id, NEW.location_id, new_qty
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_apply_stock_movement
    AFTER INSERT ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION apply_stock_movement();

-- Physical counts. Each line produces a 'stocktake' movement on approval.
CREATE TABLE stocktakes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL,
    location_id     uuid NOT NULL,
    reference       text,
    started_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    created_by      uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by)  REFERENCES users     (tenant_id, id)
);

CREATE TABLE stocktake_lines (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL,
    stocktake_id    uuid NOT NULL,
    product_id      uuid NOT NULL,
    expected_qty    numeric(14,3) NOT NULL,
    counted_qty     numeric(14,3) NOT NULL CHECK (counted_qty >= 0),
    variance        numeric(14,3) GENERATED ALWAYS AS (counted_qty - expected_qty) STORED,
    note            text,
    UNIQUE (tenant_id, stocktake_id, product_id),
    FOREIGN KEY (tenant_id, stocktake_id) REFERENCES stocktakes (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id)   REFERENCES products   (tenant_id, id)
);

-- ---------------------------------------------------------------------
-- 5. Sales
-- ---------------------------------------------------------------------

CREATE TABLE sales (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    location_id         uuid NOT NULL,
    sale_number         text NOT NULL,               -- human-facing receipt no.
    status              sale_status NOT NULL DEFAULT 'draft',
    customer_name       text,
    customer_phone      text,
    subtotal_amount     numeric(14,2) NOT NULL DEFAULT 0 CHECK (subtotal_amount >= 0),
    discount_amount     numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_amount          numeric(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    total_amount        numeric(14,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    payment_method      text,
    sold_at             timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    -- Idempotency: the Android client may retry a POST over a flaky link.
    client_request_id   uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sale_number),
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by)  REFERENCES users     (tenant_id, id)
);
CREATE UNIQUE INDEX sales_idempotency_uq ON sales (tenant_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
CREATE INDEX sales_tenant_time_idx ON sales (tenant_id, sold_at DESC);
CREATE INDEX sales_tenant_status_idx ON sales (tenant_id, status);
CREATE TRIGGER trg_sales_updated BEFORE UPDATE ON sales
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE sale_items (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL,
    sale_id         uuid NOT NULL,
    product_id      uuid NOT NULL,
    quantity        numeric(14,3) NOT NULL CHECK (quantity > 0),
    unit_price      numeric(14,4) NOT NULL CHECK (unit_price >= 0),
    unit_cost       numeric(14,4),                   -- snapshot for margin reporting
    discount_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_rate        numeric(6,4)  NOT NULL DEFAULT 0,
    line_total      numeric(14,2) GENERATED ALWAYS AS
                        (round(quantity * unit_price - discount_amount, 2)) STORED,
    FOREIGN KEY (tenant_id, sale_id)    REFERENCES sales    (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id)
);
CREATE INDEX sale_items_sale_idx    ON sale_items (tenant_id, sale_id);
CREATE INDEX sale_items_product_idx ON sale_items (tenant_id, product_id);

-- ---------------------------------------------------------------------
-- 6. Purchasing
-- ---------------------------------------------------------------------

CREATE TABLE purchase_orders (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id         uuid NOT NULL,
    location_id         uuid NOT NULL,
    po_number           text NOT NULL,
    status              po_status NOT NULL DEFAULT 'draft',
    ordered_at          timestamptz,
    expected_at         date,
    received_at         timestamptz,
    total_amount        numeric(14,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    notes               text,
    created_by          uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, po_number),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id),
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by)  REFERENCES users     (tenant_id, id)
);
CREATE INDEX purchase_orders_supplier_idx ON purchase_orders (tenant_id, supplier_id, status);
CREATE TRIGGER trg_purchase_orders_updated BEFORE UPDATE ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE purchase_order_items (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL,
    purchase_order_id   uuid NOT NULL,
    product_id          uuid NOT NULL,
    quantity_ordered    numeric(14,3) NOT NULL CHECK (quantity_ordered > 0),
    quantity_received   numeric(14,3) NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
    unit_cost           numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
    line_total          numeric(14,2) GENERATED ALWAYS AS
                            (round(quantity_ordered * unit_cost, 2)) STORED,
    UNIQUE (tenant_id, purchase_order_id, product_id),
    FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id)        REFERENCES products        (tenant_id, id)
);

-- Actual lead time achieved, measured per receipt. Feeds back into the
-- supplier's lead_time_days / stddev instead of trusting what they promised.
CREATE TABLE supplier_lead_time_observations (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           uuid NOT NULL,
    supplier_id         uuid NOT NULL,
    purchase_order_id   uuid,
    ordered_at          timestamptz NOT NULL,
    received_at         timestamptz NOT NULL,
    lead_time_days      numeric(6,2) NOT NULL CHECK (lead_time_days >= 0),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX supplier_lead_time_idx ON supplier_lead_time_observations (tenant_id, supplier_id);

-- ---------------------------------------------------------------------
-- 7. Forecasting
-- ---------------------------------------------------------------------

-- Daily demand rollup, in the TENANT's timezone. Rebuilt incrementally by a
-- scheduled job. This is the training set — keep zero-demand days, because
-- "sold nothing on Sunday" is real signal, and omitting those rows inflates
-- the average badly for slow movers.
CREATE TABLE demand_daily (
    tenant_id           uuid NOT NULL,
    product_id          uuid NOT NULL,
    location_id         uuid NOT NULL,
    day                 date NOT NULL,
    units_sold          numeric(14,3) NOT NULL DEFAULT 0 CHECK (units_sold >= 0),
    sales_count         integer       NOT NULL DEFAULT 0 CHECK (sales_count >= 0),
    revenue             numeric(14,2) NOT NULL DEFAULT 0,
    -- True if the product was out of stock for part of the day: demand was
    -- censored, so the forecaster should not read the low number as low demand.
    had_stockout        boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, product_id, location_id, day),
    FOREIGN KEY (tenant_id, product_id)  REFERENCES products  (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX demand_daily_day_idx ON demand_daily (tenant_id, day);

CREATE TABLE forecasts (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               uuid NOT NULL,
    product_id              uuid NOT NULL,
    location_id             uuid NOT NULL,
    method                  forecast_method NOT NULL,
    generated_at            timestamptz NOT NULL DEFAULT now(),
    -- Window of history the model actually saw.
    history_days            integer NOT NULL CHECK (history_days >= 0),
    history_from            date,
    history_to              date,
    avg_daily_demand        numeric(14,4) NOT NULL CHECK (avg_daily_demand >= 0),
    demand_stddev           numeric(14,4) NOT NULL DEFAULT 0 CHECK (demand_stddev >= 0),
    horizon_days            integer NOT NULL CHECK (horizon_days > 0),
    forecast_qty            numeric(14,3) NOT NULL CHECK (forecast_qty >= 0),
    -- Derived outputs the app actually displays.
    days_of_cover           numeric(10,2),           -- null = effectively infinite
    projected_stockout_on   date,
    reorder_point           numeric(14,3),
    service_level           numeric(4,3) NOT NULL DEFAULT 0.950
                                CHECK (service_level > 0 AND service_level < 1),
    confidence              numeric(4,3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    is_current              boolean NOT NULL DEFAULT true,
    FOREIGN KEY (tenant_id, product_id)  REFERENCES products  (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id) ON DELETE CASCADE
);
-- Exactly one live forecast per product/location; older rows stay for accuracy scoring.
CREATE UNIQUE INDEX forecasts_current_uq
    ON forecasts (tenant_id, product_id, location_id) WHERE is_current;
CREATE INDEX forecasts_history_idx
    ON forecasts (tenant_id, product_id, generated_at DESC);

-- Scores past forecasts against what actually happened (MAPE/bias).
CREATE TABLE forecast_accuracy (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid NOT NULL,
    forecast_id     bigint NOT NULL REFERENCES forecasts(id) ON DELETE CASCADE,
    product_id      uuid NOT NULL,
    period_start    date NOT NULL,
    period_end      date NOT NULL,
    predicted_qty   numeric(14,3) NOT NULL,
    actual_qty      numeric(14,3) NOT NULL,
    abs_pct_error   numeric(10,4),
    evaluated_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end >= period_start)
);
CREATE INDEX forecast_accuracy_product_idx ON forecast_accuracy (tenant_id, product_id, period_end DESC);

CREATE TABLE reorder_recommendations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL,
    product_id          uuid NOT NULL,
    location_id         uuid NOT NULL,
    supplier_id         uuid,
    forecast_id         bigint REFERENCES forecasts(id) ON DELETE SET NULL,
    quantity_on_hand    numeric(14,3) NOT NULL,
    reorder_point       numeric(14,3) NOT NULL,
    recommended_qty     numeric(14,3) NOT NULL CHECK (recommended_qty > 0),
    estimated_cost      numeric(14,2),
    projected_stockout_on date,
    rationale           text,                        -- plain-English reason for the app
    status              recommendation_status NOT NULL DEFAULT 'open',
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz,
    resolved_by         uuid,
    FOREIGN KEY (tenant_id, product_id)  REFERENCES products  (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_id) REFERENCES locations (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX reorder_recommendations_open_uq
    ON reorder_recommendations (tenant_id, product_id, location_id) WHERE status = 'open';

-- ---------------------------------------------------------------------
-- 8. Audit
-- ---------------------------------------------------------------------

CREATE TABLE audit_log (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid,
    user_id         uuid,
    action          text NOT NULL,                   -- e.g. 'product.updated'
    entity_type     text,
    entity_id       text,
    before_data     jsonb,
    after_data      jsonb,
    ip_address      inet,
    occurred_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_tenant_time_idx ON audit_log (tenant_id, occurred_at DESC);
CREATE INDEX audit_log_entity_idx      ON audit_log (tenant_id, entity_type, entity_id);

-- ---------------------------------------------------------------------
-- 9. Convenience views
-- ---------------------------------------------------------------------

CREATE VIEW v_stock_status AS
SELECT
    ps.tenant_id,
    ps.product_id,
    ps.location_id,
    p.sku,
    p.name,
    ps.quantity_on_hand,
    ps.quantity_available,
    COALESCE(p.reorder_point, f.reorder_point)          AS effective_reorder_point,
    f.avg_daily_demand,
    f.days_of_cover,
    f.projected_stockout_on,
    CASE
        WHEN ps.quantity_on_hand <= 0                                        THEN 'out_of_stock'
        WHEN COALESCE(p.reorder_point, f.reorder_point) IS NULL              THEN 'unknown'
        WHEN ps.quantity_available <= COALESCE(p.reorder_point, f.reorder_point) THEN 'reorder'
        ELSE 'ok'
    END AS stock_state
FROM product_stock ps
JOIN products p
     ON p.tenant_id = ps.tenant_id AND p.id = ps.product_id
LEFT JOIN forecasts f
     ON f.tenant_id = ps.tenant_id
    AND f.product_id = ps.product_id
    AND f.location_id = ps.location_id
    AND f.is_current
WHERE p.deleted_at IS NULL AND p.is_tracked;

-- ---------------------------------------------------------------------
-- 10. Row-Level Security
--
-- Every tenant-scoped table gets the same policy. FORCE applies it to the
-- table owner too, so a mistake in the service layer cannot bypass it.
-- The application connects as a NON-superuser role (app_user below);
-- superusers and BYPASSRLS roles ignore these policies entirely.
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t text;
    tenant_tables text[] := ARRAY[
        'users','refresh_tokens','locations','categories','suppliers','products',
        'product_suppliers','stock_movements','product_stock','stocktakes',
        'stocktake_lines','sales','sale_items','purchase_orders','purchase_order_items',
        'supplier_lead_time_observations','demand_daily','forecasts','forecast_accuracy',
        'reorder_recommendations','audit_log'
    ];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$
            CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id())
        $p$, t);
    END LOOP;
END $$;

-- The tenants table is visible only as your own row.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenants
    USING (id = current_tenant_id())
    WITH CHECK (id = current_tenant_id());

-- ---------------------------------------------------------------------
-- 11. Application role
--
--   CREATE ROLE app_user LOGIN PASSWORD '...';         -- no BYPASSRLS, no superuser
--   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_user;
--   GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO app_user;
--   REVOKE UPDATE, DELETE ON stock_movements FROM app_user;
--
-- Login and tenant resolution happen BEFORE app.tenant_id is known, so they
-- need a separate connection/role that can read tenants+users unscoped.
-- Keep that role's grants to exactly those two tables, SELECT only.
-- ---------------------------------------------------------------------