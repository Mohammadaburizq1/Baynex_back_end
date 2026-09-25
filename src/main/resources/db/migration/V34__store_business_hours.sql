CREATE TABLE store_business_hours (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    day_of_week VARCHAR(10) NOT NULL CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    open_24_hours BOOLEAN NOT NULL DEFAULT FALSE,
    open_time TIME,
    close_time TIME,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_store_business_hours_day UNIQUE (store_id, day_of_week),
    CONSTRAINT ck_store_business_hours_shape CHECK (
        (is_closed AND NOT open_24_hours AND open_time IS NULL AND close_time IS NULL)
        OR (NOT is_closed AND open_24_hours AND open_time IS NULL AND close_time IS NULL)
        OR (NOT is_closed AND NOT open_24_hours AND open_time IS NOT NULL AND close_time IS NOT NULL AND open_time <> close_time)
    )
);
CREATE INDEX ix_store_business_hours_store_id ON store_business_hours(store_id);
