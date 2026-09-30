-- POS-26: restaurant mode. Additive only; retail POS sales and web orders are unchanged.
--
-- A restaurant order (dine-in, takeaway, delivery) is an ordinary customer_orders row (source POS) —
-- there is no second order system. What is new is that it stays OPEN while items are added over time
-- from one or more tills, and is paid in parts before it closes. Every change arrives as an idempotent
-- operation (pos_sync_operations, types RESTAURANT_*), each line has a till-generated identity so
-- additions from different tills merge instead of overwriting, and each payment is its own row.

-- Restaurant mode per store: NULL = decided by the store's business type (restaurant/cafe templates on).
ALTER TABLE stores ADD COLUMN pos_restaurant_mode BOOLEAN;

CREATE TABLE restaurant_areas (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_restaurant_areas_store ON restaurant_areas(store_id, sort_order);

CREATE TABLE restaurant_tables (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    area_id UUID NOT NULL REFERENCES restaurant_areas(id) ON DELETE RESTRICT,
    name VARCHAR(40) NOT NULL,
    capacity INTEGER CHECK (capacity IS NULL OR capacity > 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    -- Tables are deactivated, never deleted once they have orders (history keeps pointing at them).
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
-- The name is a label, not the identity; still, two active tables of one store may not share it.
CREATE UNIQUE INDEX ux_restaurant_tables_active_name ON restaurant_tables(store_id, lower(name)) WHERE active;
CREATE INDEX ix_restaurant_tables_area ON restaurant_tables(area_id, sort_order);

-- The restaurant side of a POS order. pos_order_type NULL = a retail POS sale or a web order.
ALTER TABLE customer_orders ADD COLUMN pos_order_type VARCHAR(10);
ALTER TABLE customer_orders ADD CONSTRAINT ck_customer_orders_pos_order_type CHECK (pos_order_type IN ('DINE_IN', 'TAKEAWAY', 'DELIVERY'));
ALTER TABLE customer_orders ADD COLUMN restaurant_table_id UUID REFERENCES restaurant_tables(id) ON DELETE SET NULL;
ALTER TABLE customer_orders ADD COLUMN guest_count INTEGER CHECK (guest_count IS NULL OR guest_count > 0);
ALTER TABLE customer_orders ADD COLUMN waiter_id UUID REFERENCES app_users(id) ON DELETE SET NULL;
ALTER TABLE customer_orders ADD COLUMN waiter_name VARCHAR(160);
ALTER TABLE customer_orders ADD COLUMN original_waiter_id UUID REFERENCES app_users(id) ON DELETE SET NULL;
ALTER TABLE customer_orders ADD COLUMN original_waiter_name VARCHAR(160);
-- Readable ticket (#104) for the counter and the kitchen; the identity stays the order id / code.
ALTER TABLE customer_orders ADD COLUMN pos_ticket_number VARCHAR(20);
ALTER TABLE customer_orders ADD COLUMN pickup_name VARCHAR(160);
ALTER TABLE customer_orders ADD COLUMN delivery_zone_id UUID REFERENCES delivery_zones(id) ON DELETE SET NULL;
-- Server version of an open restaurant order: +1 per applied operation.
ALTER TABLE customer_orders ADD COLUMN pos_order_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE customer_orders ADD COLUMN pos_opened_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE customer_orders ADD COLUMN pos_closed_at TIMESTAMP WITH TIME ZONE;
-- A merged-away order keeps its row (status CANCELLED) and points at the order that absorbed it.
ALTER TABLE customer_orders ADD COLUMN pos_merged_into_order_id UUID REFERENCES customer_orders(id) ON DELETE SET NULL;
-- Restaurant orders are referred to by every till through the id the opening till generated.
CREATE INDEX ix_customer_orders_pos_local_order ON customer_orders(store_id, pos_local_order_id) WHERE pos_local_order_id IS NOT NULL;
CREATE INDEX ix_customer_orders_restaurant_open ON customer_orders(store_id, status) WHERE pos_order_type IS NOT NULL;
CREATE INDEX ix_customer_orders_table ON customer_orders(restaurant_table_id) WHERE restaurant_table_id IS NOT NULL;

-- Lines of a restaurant order: identity, note, course, who added them. A void lowers quantity and
-- total to what is still ordered (so returns and reports need no change); voided_quantity and the
-- restaurant events keep the history. Fully voided lines stay with quantity 0.
ALTER TABLE order_items DROP CONSTRAINT order_items_quantity_check;
ALTER TABLE order_items ADD CONSTRAINT order_items_quantity_check CHECK (quantity >= 0);
ALTER TABLE order_items ADD COLUMN pos_line_uid UUID;
ALTER TABLE order_items ADD COLUMN item_note VARCHAR(300);
ALTER TABLE order_items ADD COLUMN course VARCHAR(10) CHECK (course IN ('STARTER', 'MAIN', 'DESSERT', 'DRINK'));
ALTER TABLE order_items ADD COLUMN voided_quantity INTEGER NOT NULL DEFAULT 0 CHECK (voided_quantity >= 0);
ALTER TABLE order_items ADD COLUMN added_by_id UUID REFERENCES app_users(id) ON DELETE SET NULL;
ALTER TABLE order_items ADD COLUMN added_by_name VARCHAR(160);
ALTER TABLE order_items ADD COLUMN added_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_items ADD COLUMN added_device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL;
-- Kitchen foundation (POS-27): the order version that sent the line, and the line's own version
-- (+1 per change to it: void, note, move) for conflict detection between tills.
ALTER TABLE order_items ADD COLUMN sent_version INTEGER;
ALTER TABLE order_items ADD COLUMN line_version INTEGER NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX ux_order_items_pos_line_uid ON order_items(pos_line_uid) WHERE pos_line_uid IS NOT NULL;

-- Explicit payments: a restaurant bill is paid in parts, by several methods, possibly on several tills
-- and shifts. The cash parts are what the drawer of the paying till's shift holds (POS-24).
CREATE TABLE pos_order_payments (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL UNIQUE REFERENCES pos_sync_operations(operation_id) ON DELETE CASCADE,
    amount NUMERIC(12, 3) NOT NULL CHECK (amount > 0),
    method VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'EXTERNAL_TERMINAL')),
    -- How the bill was split for this payment: FULL, ITEMS, EQUAL, CUSTOM; allocation = what it covered.
    split_mode VARCHAR(10) NOT NULL CHECK (split_mode IN ('FULL', 'ITEMS', 'EQUAL', 'CUSTOM')),
    allocation TEXT,
    staff_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    staff_name VARCHAR(160) NOT NULL,
    shift_id UUID,
    device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    paid_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_pos_order_payments_order ON pos_order_payments(order_id);
CREATE INDEX ix_pos_order_payments_shift ON pos_order_payments(shift_id) WHERE shift_id IS NOT NULL;

-- Audit trail of everything done to a restaurant order (who, which till, when, what).
CREATE TABLE pos_restaurant_events (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
    operation_id UUID REFERENCES pos_sync_operations(operation_id) ON DELETE SET NULL,
    device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('OPENED', 'ITEMS_ADDED', 'ITEM_VOIDED', 'LINE_UPDATED', 'WAITER_ASSIGNED',
        'DETAILS_UPDATED', 'TABLE_MOVED', 'TABLES_MERGED', 'MERGED_INTO', 'ITEMS_MOVED_OUT', 'ITEMS_MOVED_IN', 'DISCOUNT_APPLIED',
        'DISCOUNT_REMOVED', 'PAYMENT_ADDED', 'CLOSED')),
    staff_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    staff_name VARCHAR(160),
    manager_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    manager_name VARCHAR(160),
    from_table_id UUID REFERENCES restaurant_tables(id) ON DELETE SET NULL,
    to_table_id UUID REFERENCES restaurant_tables(id) ON DELETE SET NULL,
    detail TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    order_version INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_pos_restaurant_events_order ON pos_restaurant_events(order_id, created_at);

ALTER TABLE pos_manager_overrides ADD COLUMN restaurant_event_id UUID REFERENCES pos_restaurant_events(id) ON DELETE CASCADE;

ALTER TABLE inventory_adjustments DROP CONSTRAINT inventory_adjustments_reason_check;
ALTER TABLE inventory_adjustments ADD CONSTRAINT inventory_adjustments_reason_check CHECK (reason IN
    ('INITIAL','RESTOCK','CORRECTION','DAMAGED','RETURNED','ORDER_PLACED','ORDER_CANCELLED','POS_RETURN','POS_EXCHANGE_RETURN',
     'POS_VOID'));

ALTER TABLE pos_sync_conflicts DROP CONSTRAINT ck_pos_sync_conflicts_type;
ALTER TABLE pos_sync_conflicts ADD CONSTRAINT ck_pos_sync_conflicts_type CHECK (conflict_type IN ('OVERSOLD', 'PRODUCT_DELETED',
    'PRODUCT_UNAVAILABLE', 'VARIANT_DELETED', 'VARIANT_UNAVAILABLE', 'PRODUCT_CHANGED', 'PRICE_CHANGED',
    'CUSTOMER_UNLINKED', 'DISCOUNT_CHANGED', 'DISCOUNT_LIMIT_REACHED', 'STAFF_UNAVAILABLE', 'OVERRIDE_UNVERIFIED',
    'RETURN_QUANTITY_EXCEEDED', 'RETURN_APPROVAL_MISSING', 'EXCHANGE_MISMATCH',
    'SHIFT_RECONCILIATION_MISMATCH', 'SHIFT_APPROVAL_MISSING',
    'RESTAURANT_LINE_CONFLICT', 'RESTAURANT_TABLE_OCCUPIED', 'RESTAURANT_ORDER_CLOSED', 'RESTAURANT_OVERPAID',
    'RESTAURANT_BALANCE_DUE', 'RESTAURANT_APPROVAL_MISSING'));
