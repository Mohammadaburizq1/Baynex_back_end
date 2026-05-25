-- Replace view with a physical table maintained by the application on order create/cancel.
DROP VIEW IF EXISTS daily_store_sales;

CREATE TABLE daily_store_sales (
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    sale_date DATE NOT NULL,
    store_name VARCHAR(160) NOT NULL,
    total_revenue NUMERIC(12, 3) NOT NULL DEFAULT 0,
    order_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, sale_date),
    CONSTRAINT chk_daily_store_sales_revenue_nonneg CHECK (total_revenue >= 0),
    CONSTRAINT chk_daily_store_sales_count_nonneg CHECK (order_count >= 0)
);

CREATE INDEX ix_daily_store_sales_sale_date ON daily_store_sales (sale_date DESC);

-- Backfill from existing orders (non-cancelled only).
INSERT INTO daily_store_sales (store_id, sale_date, store_name, total_revenue, order_count)
SELECT
    s.id,
    (o.created_at AT TIME ZONE 'UTC')::date,
    s.name,
    SUM(o.total),
    COUNT(*)::bigint
FROM customer_orders o
JOIN stores s ON s.id = o.store_id
WHERE o.status <> 'CANCELLED'
GROUP BY s.id, s.name, (o.created_at AT TIME ZONE 'UTC')::date;
