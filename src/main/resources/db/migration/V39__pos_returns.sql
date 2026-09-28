-- POS-23: returns and exchanges of POS sales. Additive only.
--
-- A return is its own financial record linked to the original order and its items — never a
-- negative order. It is uploaded from the till (possibly offline) through the same idempotency record
-- as sales (pos_sync_operations, operation_type RETURN_CREATE). Stock goes back only through the
-- inventory ledger (reasons POS_RETURN / POS_EXCHANGE_RETURN), exactly once per operation.
--
-- An exchange = a return (kind EXCHANGE) whose value is credited to a new, ordinary POS sale made in
-- the same till transaction; that sale records the credit it received (pos_exchange_credit).

-- The till's line number of each POS order item, so a return can name the exact line it reverses.
-- Null for web orders and for POS orders uploaded before V39.
ALTER TABLE order_items ADD COLUMN pos_line_no INTEGER;

ALTER TABLE customer_orders ADD COLUMN pos_exchange_credit NUMERIC(12, 3);
ALTER TABLE customer_orders ADD COLUMN pos_exchange_local_return_id UUID;

CREATE TABLE pos_returns (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    -- The till that made the return (kept if it is later revoked or re-activated).
    device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    operation_id UUID NOT NULL UNIQUE REFERENCES pos_sync_operations(operation_id) ON DELETE CASCADE,
    local_return_id UUID NOT NULL,
    return_number VARCHAR(40) NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('RETURN', 'EXCHANGE')),
    original_order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE RESTRICT,
    original_order_code VARCHAR(12),
    original_receipt_number VARCHAR(40),
    -- Snapshot of the original sale's customer (identity is never edited by a return).
    customer_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    customer_name VARCHAR(160),
    customer_phone VARCHAR(40),
    staff_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    staff_name VARCHAR(160),
    manager_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    manager_name VARCHAR(160),
    reason VARCHAR(30) NOT NULL CHECK (reason IN
        ('DAMAGED', 'WRONG_ITEM', 'CUSTOMER_CHANGED_MIND', 'QUALITY_ISSUE', 'DUPLICATE_SALE', 'OTHER')),
    reason_note VARCHAR(300),
    currency VARCHAR(3) NOT NULL,
    -- What the till says it gave back for the goods (validated against the original sale).
    requested_refund_total NUMERIC(12, 3) NOT NULL,
    -- The accepted value of the goods returned (≤ requested; less only when quantity was already used up).
    refund_total NUMERIC(12, 3) NOT NULL,
    -- Part of the till's refund applied to the exchange sale instead of being handed out.
    exchange_credit NUMERIC(12, 3) NOT NULL DEFAULT 0,
    -- Part handed back to the customer (cash or on the external card terminal).
    refund_paid_out NUMERIC(12, 3) NOT NULL,
    refund_method VARCHAR(20) CHECK (refund_method IN ('CASH', 'EXTERNAL_TERMINAL')),
    exchange_local_order_id UUID,
    status VARCHAR(30) NOT NULL CHECK (status IN ('SYNCED', 'SYNCED_WITH_CONFLICTS')),
    returned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ux_pos_returns_local UNIQUE (device_id, local_return_id),
    CONSTRAINT ck_pos_returns_amounts CHECK (refund_total >= 0 AND requested_refund_total >= refund_total
        AND exchange_credit >= 0 AND refund_paid_out >= 0)
);
CREATE INDEX ix_pos_returns_store ON pos_returns(store_id, created_at);
CREATE INDEX ix_pos_returns_order ON pos_returns(original_order_id);

CREATE TABLE pos_return_items (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    return_id UUID NOT NULL REFERENCES pos_returns(id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE RESTRICT,
    pos_line_no INTEGER,
    product_id UUID,
    variant_id UUID,
    item_name VARCHAR(300) NOT NULL,
    requested_quantity INTEGER NOT NULL CHECK (requested_quantity > 0),
    -- Accepted quantity: never more than sold minus earlier accepted returns of this line.
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    unit_price NUMERIC(12, 3) NOT NULL,
    -- The line's paid value (line total minus its share of the order discount).
    line_paid_total NUMERIC(12, 3) NOT NULL,
    requested_refund NUMERIC(12, 3) NOT NULL,
    refund_amount NUMERIC(12, 3) NOT NULL,
    disposition VARCHAR(20) NOT NULL CHECK (disposition IN ('RESTOCK', 'DAMAGED', 'DO_NOT_RESTOCK')),
    restocked_quantity INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_pos_return_items_order_item ON pos_return_items(order_item_id);

-- Manager approvals given for a return are kept in the same audit table as sale approvals.
ALTER TABLE pos_manager_overrides ADD COLUMN return_id UUID REFERENCES pos_returns(id) ON DELETE CASCADE;

ALTER TABLE inventory_adjustments DROP CONSTRAINT inventory_adjustments_reason_check;
ALTER TABLE inventory_adjustments ADD CONSTRAINT inventory_adjustments_reason_check CHECK (reason IN
    ('INITIAL','RESTOCK','CORRECTION','DAMAGED','RETURNED','ORDER_PLACED','ORDER_CANCELLED','POS_RETURN','POS_EXCHANGE_RETURN'));

ALTER TABLE pos_sync_conflicts DROP CONSTRAINT ck_pos_sync_conflicts_type;
ALTER TABLE pos_sync_conflicts ADD CONSTRAINT ck_pos_sync_conflicts_type CHECK (conflict_type IN ('OVERSOLD', 'PRODUCT_DELETED',
    'PRODUCT_UNAVAILABLE', 'VARIANT_DELETED', 'VARIANT_UNAVAILABLE', 'PRODUCT_CHANGED', 'PRICE_CHANGED',
    'CUSTOMER_UNLINKED', 'DISCOUNT_CHANGED', 'DISCOUNT_LIMIT_REACHED', 'STAFF_UNAVAILABLE', 'OVERRIDE_UNVERIFIED',
    'RETURN_QUANTITY_EXCEEDED', 'RETURN_APPROVAL_MISSING', 'EXCHANGE_MISMATCH'));
