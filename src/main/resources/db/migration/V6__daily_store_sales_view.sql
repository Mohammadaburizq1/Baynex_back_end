-- Per-store revenue aggregated by calendar day (UTC). Excludes cancelled orders from totals.
CREATE OR REPLACE VIEW daily_store_sales AS
SELECT
    s.id AS store_id,
    s.name AS store_name,
    (o.created_at AT TIME ZONE 'UTC')::date AS sale_date,
    SUM(o.total) AS total_revenue,
    COUNT(*)::bigint AS order_count
FROM customer_orders o
JOIN stores s ON s.id = o.store_id
WHERE o.status <> 'CANCELLED'
GROUP BY s.id, s.name, (o.created_at AT TIME ZONE 'UTC')::date;
