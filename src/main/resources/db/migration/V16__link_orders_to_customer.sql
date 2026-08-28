ALTER TABLE customer_orders ADD COLUMN customer_id UUID REFERENCES app_users(id);
CREATE INDEX idx_customer_orders_customer_id ON customer_orders(customer_id);
