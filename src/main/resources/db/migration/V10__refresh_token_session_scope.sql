ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS session_scope VARCHAR(20) NOT NULL DEFAULT 'MERCHANT';

UPDATE refresh_tokens SET session_scope = 'MERCHANT' WHERE session_scope IS NULL OR session_scope = '';

ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS refresh_tokens_session_scope_check;
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_session_scope_check
    CHECK (session_scope IN ('MERCHANT', 'ADMIN'));
