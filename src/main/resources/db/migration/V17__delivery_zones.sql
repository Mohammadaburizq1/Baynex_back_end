CREATE TABLE delivery_zones (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    areas TEXT,
    min_order NUMERIC(12,3) NOT NULL DEFAULT 0,
    delivery_fee NUMERIC(12,3) NOT NULL DEFAULT 0,
    estimated_time VARCHAR(60),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_zones_store_id ON delivery_zones(store_id);

ALTER TABLE stores ADD COLUMN free_delivery_threshold NUMERIC(12,3);
ALTER TABLE stores ADD COLUMN default_estimated_time VARCHAR(60);
ALTER TABLE stores ADD COLUMN pickup_available BOOLEAN NOT NULL DEFAULT TRUE;
