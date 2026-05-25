-- Expand roles and migrate legacy MERCHANT -> MERCHANT_OWNER

ALTER TABLE app_users DROP CONSTRAINT IF EXISTS app_users_role_check;
UPDATE app_users SET role = 'MERCHANT_OWNER' WHERE role = 'MERCHANT';
ALTER TABLE app_users ADD CONSTRAINT app_users_role_check CHECK (role IN (
    'SUPER_ADMIN',
    'SUPPORT_ADMIN',
    'FINANCE_ADMIN',
    'READ_ONLY_ADMIN',
    'MERCHANT_OWNER',
    'MERCHANT_STAFF',
    'CUSTOMER'
));

-- Require MFA for super admins (can complete setup via admin panel later)
UPDATE app_users SET mfa_enabled = TRUE WHERE role = 'SUPER_ADMIN';

-- Public order lookup code (short reference for customers)
ALTER TABLE customer_orders ADD COLUMN IF NOT EXISTS order_code VARCHAR(12);
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_orders_store_order_code
    ON customer_orders (store_id, order_code) WHERE order_code IS NOT NULL;

-- Backfill order codes for existing rows
UPDATE customer_orders
SET order_code = upper(substring(replace(id::text, '-', '') from 1 for 8))
WHERE order_code IS NULL;

ALTER TABLE customer_orders ALTER COLUMN order_code SET NOT NULL;
