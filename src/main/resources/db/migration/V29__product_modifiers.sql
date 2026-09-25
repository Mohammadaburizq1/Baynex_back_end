-- Add-ons / modifiers: customisations a customer picks when ordering a product ("Size: Large +2.00",
-- "Extras: cheese +1.50, bacon +2.00", "No onions"). Unlike variants they carry no stock or SKU of
-- their own — they only adjust the price of the line — and they belong to one product.
--
-- min_select / max_select express the rules: min 0 = optional, min >= 1 = required; max 1 = pick
-- one, max N = pick up to N. price_delta is never negative, so a line can't be discounted below its
-- base price through an add-on.
CREATE TABLE product_modifier_groups (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    min_select INTEGER NOT NULL DEFAULT 0 CHECK (min_select >= 0),
    max_select INTEGER NOT NULL DEFAULT 1 CHECK (max_select >= 1),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (max_select >= min_select)
);
CREATE INDEX ix_product_modifier_groups_product_id ON product_modifier_groups(product_id);
CREATE INDEX ix_product_modifier_groups_store_id ON product_modifier_groups(store_id);

CREATE TABLE product_modifier_options (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES product_modifier_groups(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    price_delta NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (price_delta >= 0),
    is_preselected BOOLEAN NOT NULL DEFAULT FALSE,
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_product_modifier_options_group_id ON product_modifier_options(group_id);
CREATE INDEX ix_product_modifier_options_store_id ON product_modifier_options(store_id);

-- What was chosen, copied onto the order line at purchase time. Deliberately no foreign key back to
-- product_modifier_options: renaming, repricing or deleting an add-on afterwards must never change
-- what an existing order says the customer got (or paid). order_items.unit_price already includes
-- these deltas, so totals and the sales analytics need no change.
CREATE TABLE order_item_modifiers (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    group_name VARCHAR(80) NOT NULL,
    option_name VARCHAR(80) NOT NULL,
    price_delta NUMERIC(12,3) NOT NULL DEFAULT 0
);
CREATE INDEX ix_order_item_modifiers_order_item_id ON order_item_modifiers(order_item_id);
