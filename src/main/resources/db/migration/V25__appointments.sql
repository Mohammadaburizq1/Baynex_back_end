CREATE TABLE appointment_slots (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL CHECK (ends_at > starts_at),
    capacity INTEGER NOT NULL DEFAULT 1 CHECK (capacity > 0),
    booked_count INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_appointment_slots_store_id ON appointment_slots(store_id);

CREATE TABLE appointments (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    slot_id UUID NOT NULL REFERENCES appointment_slots(id) ON DELETE CASCADE,
    product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    customer_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    customer_name VARCHAR(160) NOT NULL,
    customer_email VARCHAR(255),
    customer_phone VARCHAR(40) NOT NULL,
    notes VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED','CANCELLED','COMPLETED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_appointments_store_id ON appointments(store_id);
CREATE INDEX ix_appointments_slot_id ON appointments(slot_id);

-- Extend the staff_permissions grid with the new section — same alter-a-CHECK-constraint
-- pattern already used once in this schema (V22, customer_orders_customer_id_fkey).
ALTER TABLE staff_permissions DROP CONSTRAINT staff_permissions_section_check;
ALTER TABLE staff_permissions ADD CONSTRAINT staff_permissions_section_check
    CHECK (section IN ('PRODUCTS','ORDERS','DELIVERY','CUSTOMERS','REPORTS','OFFERS','APPOINTMENTS'));
