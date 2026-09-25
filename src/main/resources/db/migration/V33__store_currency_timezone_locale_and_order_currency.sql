ALTER TABLE stores
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'JOD',
    ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    ADD COLUMN locale VARCHAR(10) NOT NULL DEFAULT 'en';

ALTER TABLE customer_orders
    ADD COLUMN currency VARCHAR(3);

-- Historical orders intentionally remain NULL: the pre-M1-02 schema stored only
-- numeric totals, while backend storage and frontend display used conflicting
-- currency assumptions. New orders receive the store currency in OrderService.
