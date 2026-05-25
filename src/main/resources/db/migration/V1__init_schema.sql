CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    phone VARCHAR(40),
    role VARCHAR(30) NOT NULL CHECK (role IN ('SUPER_ADMIN','MERCHANT','CUSTOMER')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    token_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_app_users_email_lower ON app_users (lower(email));
CREATE INDEX ix_app_users_created_at ON app_users (created_at);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_token_hash VARCHAR(128),
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);

CREATE TABLE stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(1200),
    logo_url VARCHAR(500),
    cover_image_url VARCHAR(500),
    phone VARCHAR(40),
    whatsapp_number VARCHAR(40),
    email VARCHAR(255),
    address VARCHAR(500),
    city VARCHAR(120),
    country VARCHAR(120),
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    primary_color VARCHAR(20),
    secondary_color VARCHAR(20),
    category_slug VARCHAR(120) NOT NULL,
    sub_category_slug VARCHAR(120),
    template_key VARCHAR(120),
    status VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_stores_owner_id ON stores (owner_id);
CREATE INDEX ix_stores_category_slug ON stores (category_slug);
CREATE INDEX ix_stores_created_at ON stores (created_at);

CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID REFERENCES stores(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    name_en VARCHAR(160) NOT NULL,
    name_ar VARCHAR(160),
    slug VARCHAR(120) NOT NULL,
    description VARCHAR(800),
    icon VARCHAR(120),
    image_url VARCHAR(500),
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    category_type VARCHAR(30) NOT NULL CHECK (category_type IN ('BUSINESS','PRODUCT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_categories_global_slug ON categories (slug) WHERE store_id IS NULL;
CREATE UNIQUE INDEX ux_categories_store_slug ON categories (store_id, slug) WHERE store_id IS NOT NULL;
CREATE INDEX ix_categories_store_id ON categories (store_id);
CREATE INDEX ix_categories_parent_id ON categories (parent_id);
CREATE INDEX ix_categories_created_at ON categories (created_at);

CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    name_en VARCHAR(180) NOT NULL,
    name_ar VARCHAR(180),
    slug VARCHAR(140) NOT NULL,
    description VARCHAR(2000),
    price NUMERIC(12,3) NOT NULL CHECK (price >= 0),
    sale_price NUMERIC(12,3) CHECK (sale_price IS NULL OR sale_price >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'JOD',
    image_url VARCHAR(500),
    gallery_json TEXT,
    sku VARCHAR(120),
    product_type VARCHAR(30) NOT NULL CHECK (product_type IN ('PRODUCT','SERVICE','FOOD_ITEM')),
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    is_featured BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, slug)
);
CREATE INDEX ix_products_store_id ON products (store_id);
CREATE INDEX ix_products_category_id ON products (category_id);
CREATE INDEX ix_products_created_at ON products (created_at);

CREATE TABLE customer_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    customer_name VARCHAR(160) NOT NULL,
    customer_email VARCHAR(255),
    customer_phone VARCHAR(40) NOT NULL,
    customer_address VARCHAR(600),
    delivery_method VARCHAR(30) NOT NULL CHECK (delivery_method IN ('DELIVERY','PICKUP')),
    payment_method VARCHAR(30) NOT NULL CHECK (payment_method IN ('CASH','CARD','WHATSAPP_ONLY')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('NEW','CONFIRMED','PREPARING','READY','DELIVERED','CANCELLED')),
    subtotal NUMERIC(12,3) NOT NULL CHECK (subtotal >= 0),
    delivery_fee NUMERIC(12,3) NOT NULL CHECK (delivery_fee >= 0),
    discount NUMERIC(12,3) NOT NULL CHECK (discount >= 0),
    total NUMERIC(12,3) NOT NULL CHECK (total >= 0),
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_customer_orders_store_id ON customer_orders (store_id);
CREATE INDEX ix_customer_orders_created_at ON customer_orders (created_at);
CREATE INDEX ix_customer_orders_email ON customer_orders (customer_email);

CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
    product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    product_name_snapshot VARCHAR(180) NOT NULL,
    unit_price NUMERIC(12,3) NOT NULL CHECK (unit_price >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    total NUMERIC(12,3) NOT NULL CHECK (total >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_items_order_id ON order_items (order_id);
CREATE INDEX ix_order_items_product_id ON order_items (product_id);

CREATE TABLE store_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_slug VARCHAR(120) NOT NULL,
    sub_category_slug VARCHAR(120),
    template_key VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(800),
    preview_image_url VARCHAR(500),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_store_templates_category_slug ON store_templates (category_slug);
CREATE INDEX ix_store_templates_created_at ON store_templates (created_at);
