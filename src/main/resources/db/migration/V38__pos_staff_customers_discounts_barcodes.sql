-- POS-12..16: what offline POS sales need on top of V37, all additive.
--
--   * barcodes on products and variants (POS-16) — the catalog had SKUs only;
--   * a POS section in the existing staff permission grid, and each user's POS PIN as a salted
--     PBKDF2 hash (POS-14) — the PIN itself is never stored;
--   * which cashier made a POS sale, and which manager approved what (POS-14 audit trail);
--   * new conflict kinds for customers, discounts and staff on uploaded sales (POS-12/13/14).
--
-- Customers (POS-12) need no table: a store's customers stay what they already are — accounts and
-- guest contacts derived from its orders (OrderRepository.queryCustomerSummaries). Discounts
-- (POS-13) stay the existing offers; the POS validates against the offers in its price book.

ALTER TABLE products ADD COLUMN barcode VARCHAR(64);
ALTER TABLE product_variants ADD COLUMN barcode VARCHAR(64);
-- One barcode namespace per store across products and variants; the cross-table half is checked
-- in the service, these indexes make each table's half a hard guarantee and the lookup fast.
CREATE UNIQUE INDEX ux_products_store_barcode ON products(store_id, barcode) WHERE barcode IS NOT NULL;
CREATE UNIQUE INDEX ux_product_variants_store_barcode ON product_variants(store_id, barcode) WHERE barcode IS NOT NULL;

-- POS: VIEW = cashier (sell, see the till's orders), EDIT = POS manager (settings, printer,
-- approvals). Unlike other sections, a staff member with no POS row is a cashier, not a manager
-- (see PosStaffService); no existing staff member gains manager rights from this migration.
ALTER TABLE staff_permissions DROP CONSTRAINT staff_permissions_section_check;
ALTER TABLE staff_permissions ADD CONSTRAINT staff_permissions_section_check
    CHECK (section IN ('PRODUCTS','ORDERS','DELIVERY','CUSTOMERS','REPORTS','OFFERS','APPOINTMENTS','STOREFRONT','POS'));

CREATE TABLE pos_staff_pins (
    user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
    -- One PIN per person (an owner of several stores uses the same PIN at each of them).
    -- PBKDF2-HMAC-SHA256 over the PIN. Salt and iterations travel with the hash to the store's POS
    -- devices so they can verify the PIN offline; the PIN is never stored anywhere.
    pin_salt VARCHAR(64) NOT NULL,
    pin_hash VARCHAR(128) NOT NULL,
    iterations INTEGER NOT NULL,
    set_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- An offline credential is not permanent: after this the PIN must be set again online.
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE customer_orders ADD COLUMN pos_staff_id UUID REFERENCES app_users(id) ON DELETE SET NULL;
ALTER TABLE customer_orders ADD COLUMN pos_staff_name VARCHAR(160);

-- Sensitive POS actions a manager approved with their PIN at the till (large discount, retrying a
-- refused upload…). Uploaded with the sale they belong to; never silent.
CREATE TABLE pos_manager_overrides (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    order_id UUID REFERENCES customer_orders(id) ON DELETE CASCADE,
    action VARCHAR(40) NOT NULL,
    acting_staff_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    acting_staff_name VARCHAR(160),
    manager_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    manager_name VARCHAR(160) NOT NULL,
    detail VARCHAR(300),
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- False when the server could not confirm the approver was a POS manager at upload time.
    verified BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_pos_manager_overrides_store ON pos_manager_overrides(store_id, created_at);
CREATE INDEX ix_pos_manager_overrides_order ON pos_manager_overrides(order_id);

ALTER TABLE pos_sync_conflicts DROP CONSTRAINT ck_pos_sync_conflicts_type;
ALTER TABLE pos_sync_conflicts ADD CONSTRAINT ck_pos_sync_conflicts_type CHECK (conflict_type IN ('OVERSOLD', 'PRODUCT_DELETED',
    'PRODUCT_UNAVAILABLE', 'VARIANT_DELETED', 'VARIANT_UNAVAILABLE', 'PRODUCT_CHANGED', 'PRICE_CHANGED',
    'CUSTOMER_UNLINKED', 'DISCOUNT_CHANGED', 'DISCOUNT_LIMIT_REACHED', 'STAFF_UNAVAILABLE', 'OVERRIDE_UNVERIFIED'));
