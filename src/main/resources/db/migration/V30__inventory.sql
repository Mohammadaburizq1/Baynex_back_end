-- Low-stock threshold per product / variant. NULL = use the store-wide default
-- (app.inventory.default-low-stock-threshold), so the common case needs no configuration.
ALTER TABLE products ADD COLUMN low_stock_threshold INTEGER CHECK (low_stock_threshold IS NULL OR low_stock_threshold >= 0);
ALTER TABLE product_variants ADD COLUMN low_stock_threshold INTEGER CHECK (low_stock_threshold IS NULL OR low_stock_threshold >= 0);

-- Append-only stock history: every change to a tracked count — a manual adjustment, a sale, a
-- cancellation, the initial count — writes one row, so "why is this at 3?" always has an answer.
--
-- Nothing here is ever updated, and item_name is copied in as text so a row stays readable after
-- the product or variant is renamed or deleted. variant_id is ON DELETE SET NULL for the same
-- reason (the history of a removed variant survives, attached to its product); the whole history
-- of a product goes with the product. reference carries the order code for sale/cancel rows.
CREATE TABLE inventory_adjustments (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    variant_id UUID REFERENCES product_variants(id) ON DELETE SET NULL,
    item_name VARCHAR(300) NOT NULL,
    delta INTEGER NOT NULL,
    stock_after INTEGER NOT NULL CHECK (stock_after >= 0),
    reason VARCHAR(30) NOT NULL CHECK (reason IN
        ('INITIAL','RESTOCK','CORRECTION','DAMAGED','RETURNED','ORDER_PLACED','ORDER_CANCELLED')),
    reference VARCHAR(60),
    note VARCHAR(300),
    created_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_inventory_adjustments_store_created ON inventory_adjustments(store_id, created_at);
CREATE INDEX ix_inventory_adjustments_product_id ON inventory_adjustments(product_id);
CREATE INDEX ix_inventory_adjustments_variant_id ON inventory_adjustments(variant_id);
