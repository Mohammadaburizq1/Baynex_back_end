-- A registered point-of-sale installation, bound to exactly one store (POS-02).
-- The owner creates the device in the dashboard and receives a short-lived, single-use activation
-- code; the POS app exchanges it for a device credential. Only SHA-256 hashes of the code and of
-- the credential are stored; the plaintext values exist only in the response that issues them.
CREATE TABLE pos_devices (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    activation_code_hash VARCHAR(128),
    activation_code_expires_at TIMESTAMP WITH TIME ZONE,
    credential_hash VARCHAR(128),
    credential_expires_at TIMESTAMP WITH TIME ZONE,
    installation_id VARCHAR(64),
    platform VARCHAR(20),
    app_version VARCHAR(40),
    activated_at TIMESTAMP WITH TIME ZONE,
    last_seen_at TIMESTAMP WITH TIME ZONE,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_pos_devices_status CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED'))
);
CREATE INDEX ix_pos_devices_store_id ON pos_devices(store_id);
CREATE UNIQUE INDEX ux_pos_devices_activation_code_hash ON pos_devices(activation_code_hash) WHERE activation_code_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_pos_devices_credential_hash ON pos_devices(credential_hash) WHERE credential_hash IS NOT NULL;
