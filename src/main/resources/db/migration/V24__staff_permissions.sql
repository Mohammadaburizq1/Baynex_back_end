CREATE TABLE staff_permissions (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    section VARCHAR(20) NOT NULL CHECK (section IN ('PRODUCTS','ORDERS','DELIVERY','CUSTOMERS','REPORTS','OFFERS')),
    level VARCHAR(10) NOT NULL CHECK (level IN ('NONE','VIEW','EDIT')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (user_id, section)
);
CREATE INDEX ix_staff_permissions_user_id ON staff_permissions(user_id);
