-- Test-only companion to the entity-generated H2 schema. This table has no JPA entity.
-- Production definition remains V7; production migrations are not changed by M1-07.
CREATE TABLE IF NOT EXISTS daily_store_sales (
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    sale_date DATE NOT NULL,
    store_name VARCHAR(160) NOT NULL,
    total_revenue NUMERIC(12, 3) NOT NULL DEFAULT 0,
    order_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, sale_date),
    CHECK (total_revenue >= 0),
    CHECK (order_count >= 0)
);
