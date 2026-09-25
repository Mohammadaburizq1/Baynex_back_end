-- Product options → variants (Size × Color, …). Additive: a product with has_variants = FALSE
-- (every existing row) keeps selling exactly as before from its own price/sku/stock. Once a product
-- has variants the variant is the purchasable unit; the product's displayed price and stock are
-- derived from its variants when read, never stored, so there is nothing to keep in sync.
--
-- Every table carries its own store_id (NOT NULL) so every lookup can be scoped to the tenant
-- directly instead of trusting a join through products. Column names avoid words H2 treats as
-- reserved (VALUE, POSITION) — these migrations also run against H2 in the integration tests.
ALTER TABLE products ADD COLUMN has_variants BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE product_options (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (product_id, name)
);
CREATE INDEX ix_product_options_product_id ON product_options(product_id);
CREATE INDEX ix_product_options_store_id ON product_options(store_id);

CREATE TABLE product_option_values (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    option_id UUID NOT NULL REFERENCES product_options(id) ON DELETE CASCADE,
    label VARCHAR(80) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    UNIQUE (option_id, label)
);
CREATE INDEX ix_product_option_values_option_id ON product_option_values(option_id);

-- options_key identifies the combination: the ids of the chosen option values, in option order,
-- comma-joined. Ids (not labels) so renaming "M" to "Medium" never changes a variant's identity.
-- UNIQUE(store_id, sku) treats NULL skus as distinct, so any number of variants may have none;
-- case-insensitive SKU uniqueness across products AND variants is enforced by the service.
CREATE TABLE product_variants (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    options_key VARCHAR(140) NOT NULL,
    sku VARCHAR(120),
    price NUMERIC(12,3) NOT NULL CHECK (price >= 0),
    sale_price NUMERIC(12,3) CHECK (sale_price IS NULL OR sale_price >= 0),
    stock INTEGER CHECK (stock IS NULL OR stock >= 0),
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (product_id, options_key),
    UNIQUE (store_id, sku)
);
CREATE INDEX ix_product_variants_product_id ON product_variants(product_id);
CREATE INDEX ix_product_variants_store_id ON product_variants(store_id);

CREATE TABLE product_variant_values (
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    option_value_id UUID NOT NULL REFERENCES product_option_values(id) ON DELETE CASCADE,
    PRIMARY KEY (variant_id, option_value_id)
);

-- What was bought. ON DELETE SET NULL for the same reason as order_items.product_id: removing a
-- variant from the catalogue must not delete or corrupt the orders that already sold it. The label
-- and SKU are copied onto the line at purchase time so history still reads correctly afterwards.
ALTER TABLE order_items ADD COLUMN variant_id UUID REFERENCES product_variants(id) ON DELETE SET NULL;
ALTER TABLE order_items ADD COLUMN variant_label VARCHAR(200);
ALTER TABLE order_items ADD COLUMN sku_snapshot VARCHAR(120);
CREATE INDEX ix_order_items_variant_id ON order_items(variant_id);
