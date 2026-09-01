CREATE TABLE offers (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    code VARCHAR(40) NOT NULL,
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENTAGE','FIXED_AMOUNT')),
    discount_value NUMERIC(12,3) NOT NULL CHECK (discount_value > 0),
    -- percentage capped at 100 the same way sale_price is capped at >= 0 elsewhere in this schema
    CHECK (discount_type <> 'PERCENTAGE' OR discount_value <= 100),
    min_order_amount NUMERIC(12,3) CHECK (min_order_amount IS NULL OR min_order_amount >= 0),
    max_uses INTEGER CHECK (max_uses IS NULL OR max_uses > 0),
    times_used INTEGER NOT NULL DEFAULT 0,
    starts_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (store_id, code)
);
CREATE INDEX ix_offers_store_id ON offers(store_id);

-- Which offer (if any) an order redeemed. ON DELETE SET NULL — deleting an offer definition
-- shouldn't delete the historical orders that used it, same reasoning as every other
-- optional-link FK in this schema (order_items.product_id, app_users.store_id).
ALTER TABLE customer_orders ADD COLUMN offer_id UUID REFERENCES offers(id) ON DELETE SET NULL;
