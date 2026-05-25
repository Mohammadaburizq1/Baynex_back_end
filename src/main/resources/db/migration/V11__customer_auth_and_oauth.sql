-- Customer refresh scope + Google account linking

ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS refresh_tokens_session_scope_check;
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_session_scope_check
    CHECK (session_scope IN ('MERCHANT', 'ADMIN', 'CUSTOMER'));

ALTER TABLE app_users ADD COLUMN IF NOT EXISTS google_sub VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_users_google_sub ON app_users (google_sub) WHERE google_sub IS NOT NULL;
