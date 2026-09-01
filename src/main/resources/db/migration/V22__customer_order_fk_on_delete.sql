-- customer_orders.customer_id was added (V16) with no ON DELETE action, meaning the default
-- NO ACTION: deleting a User row referenced by any order would fail outright. Nothing currently
-- deletes a User (no account-deletion feature exists), so this has never bitten anyone — but it's
-- a known gap flagged since the db-schema-review. ON DELETE SET NULL matches the sibling patterns
-- already used for order_items.product_id and app_users.store_id: the order survives (its
-- customer_name/phone/email snapshot fields are unaffected either way), just loses the link to an
-- account that no longer exists, rather than blocking the delete or cascading order history away.
ALTER TABLE customer_orders DROP CONSTRAINT IF EXISTS customer_orders_customer_id_fkey;
ALTER TABLE customer_orders ADD CONSTRAINT customer_orders_customer_id_fkey
    FOREIGN KEY (customer_id) REFERENCES app_users(id) ON DELETE SET NULL;
