CREATE TABLE store_creation_idempotency_keys (
    idempotency_key UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_store_creation_idempotency_keys_store_id ON store_creation_idempotency_keys(store_id);
