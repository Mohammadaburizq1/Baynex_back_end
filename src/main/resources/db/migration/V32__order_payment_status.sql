-- Payment status, tracked separately from payment_method (how the customer intends to pay) and
-- from status (fulfillment) — see PaymentStatus.java. Existing orders default to UNPAID; going
-- forward OrderService.createPublicOrder picks CASH/WHATSAPP_ONLY -> UNPAID, CARD -> PENDING.
ALTER TABLE customer_orders ADD COLUMN payment_status VARCHAR(30) NOT NULL DEFAULT 'UNPAID'
    CHECK (payment_status IN ('UNPAID','PENDING','PAID','FAILED','REFUNDED','PARTIALLY_REFUNDED'));
