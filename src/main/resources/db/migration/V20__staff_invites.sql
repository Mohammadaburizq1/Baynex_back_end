-- A MERCHANT_STAFF account belongs to exactly one store. Nullable because it's meaningless for
-- every other role (owner, admin, customer). ON DELETE SET NULL rather than CASCADE: deleting a
-- store shouldn't delete the staff member's account, just leave them unassigned.
ALTER TABLE app_users ADD COLUMN store_id UUID REFERENCES stores(id) ON DELETE SET NULL;
CREATE INDEX ix_app_users_store_id ON app_users(store_id);

CREATE TABLE staff_invites (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    invited_by UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    full_name VARCHAR(160),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_staff_invites_store_id ON staff_invites(store_id);
CREATE INDEX ix_staff_invites_expires_at ON staff_invites(expires_at);
