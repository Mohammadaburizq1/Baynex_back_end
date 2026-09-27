-- POS-07..11: offline POS sales uploaded into the existing order domain.
--
-- A POS sale becomes an ordinary customer_orders row (source = 'POS'), so the dashboard, reports,
-- stock ledger and daily sales all see it with no second order or inventory model. The tables
-- below only add what offline sync needs on top of that:
--   * pos_price_books / pos_catalog_deliveries: which prices each device was actually given, so a
--     sale completed offline can be validated against the catalog it was rung up on;
--   * pos_sync_operations: one row per uploaded operation id (the idempotency record);
--   * pos_sync_conflicts: what the merchant must review (oversold stock, products removed or
--     repriced between the sale and its upload).

ALTER TABLE customer_orders ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'WEB';
ALTER TABLE customer_orders ADD CONSTRAINT ck_customer_orders_source CHECK (source IN ('WEB', 'POS'));
ALTER TABLE customer_orders ADD COLUMN pos_device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL;
ALTER TABLE customer_orders ADD COLUMN pos_local_order_id UUID;
ALTER TABLE customer_orders ADD COLUMN pos_receipt_number VARCHAR(40);
-- A sale from one device can only ever become one order, whatever operation id carries it.
CREATE UNIQUE INDEX ux_customer_orders_pos_local_order
    ON customer_orders(pos_device_id, pos_local_order_id) WHERE pos_local_order_id IS NOT NULL;

-- Immutable, content-addressed price list of one catalog snapshot (prices, variants, add-ons).
CREATE TABLE pos_price_books (
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    price_book_hash VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, price_book_hash)
);

-- A catalog version the server actually sent (or confirmed unchanged) to a device.
CREATE TABLE pos_catalog_deliveries (
    device_id UUID NOT NULL REFERENCES pos_devices(id) ON DELETE CASCADE,
    catalog_version VARCHAR(64) NOT NULL,
    store_id UUID NOT NULL,
    price_book_hash VARCHAR(64) NOT NULL,
    first_delivered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, catalog_version),
    FOREIGN KEY (store_id, price_book_hash) REFERENCES pos_price_books(store_id, price_book_hash) ON DELETE CASCADE
);

CREATE TABLE pos_sync_operations (
    operation_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    -- The device that made the sale, and the (possibly re-activated) device that uploaded it.
    device_id UUID NOT NULL REFERENCES pos_devices(id) ON DELETE CASCADE,
    submitted_by_device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    operation_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    catalog_version VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_id UUID REFERENCES customer_orders(id) ON DELETE SET NULL,
    order_code VARCHAR(12),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_pos_sync_operations_status CHECK (status IN ('APPLYING', 'SYNCED', 'SYNCED_WITH_CONFLICTS')),
    CONSTRAINT ux_pos_sync_operations_entity UNIQUE (device_id, operation_type, entity_id)
);
CREATE INDEX ix_pos_sync_operations_store ON pos_sync_operations(store_id, created_at);

CREATE TABLE pos_sync_conflicts (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    operation_id UUID NOT NULL REFERENCES pos_sync_operations(operation_id) ON DELETE CASCADE,
    order_id UUID REFERENCES customer_orders(id) ON DELETE SET NULL,
    order_code VARCHAR(12),
    receipt_number VARCHAR(40),
    conflict_type VARCHAR(30) NOT NULL,
    product_id UUID,
    variant_id UUID,
    item_name VARCHAR(300) NOT NULL,
    requested_quantity INTEGER,
    applied_quantity INTEGER,
    shortfall INTEGER,
    stock_before INTEGER,
    stock_after INTEGER,
    sale_unit_price NUMERIC(12, 3),
    current_unit_price NUMERIC(12, 3),
    detail VARCHAR(400) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    resolution_note VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_pos_sync_conflicts_type CHECK (conflict_type IN ('OVERSOLD', 'PRODUCT_DELETED', 'PRODUCT_UNAVAILABLE',
        'VARIANT_DELETED', 'VARIANT_UNAVAILABLE', 'PRODUCT_CHANGED', 'PRICE_CHANGED')),
    CONSTRAINT ck_pos_sync_conflicts_status CHECK (status IN ('OPEN', 'RESOLVED'))
);
CREATE INDEX ix_pos_sync_conflicts_store ON pos_sync_conflicts(store_id, status, created_at);
