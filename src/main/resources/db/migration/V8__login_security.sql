-- Login security: account fields, attempts, blocklist, events, admin audit

ALTER TABLE app_users ADD COLUMN IF NOT EXISTS failed_login_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_failed_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_successful_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_login_ip VARCHAR(64);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_login_user_agent VARCHAR(512);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS mfa_secret VARCHAR(255);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS force_password_reset BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS suspicious_activity_flag BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS admin_unlock_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE login_attempts (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    ip_address VARCHAR(64) NOT NULL,
    user_agent VARCHAR(512),
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(64),
    risk_score INTEGER NOT NULL DEFAULT 0,
    country VARCHAR(120),
    city VARCHAR(120),
    device_fingerprint VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_attempts_email_created ON login_attempts (email, created_at DESC);
CREATE INDEX ix_login_attempts_ip_created ON login_attempts (ip_address, created_at DESC);
CREATE INDEX ix_login_attempts_user_created ON login_attempts (user_id, created_at DESC);
CREATE INDEX ix_login_attempts_success_created ON login_attempts (success, created_at DESC);

CREATE TABLE ip_blocklist (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    ip_address VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE,
    permanent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by_admin_id UUID REFERENCES app_users(id) ON DELETE SET NULL
);
CREATE INDEX ix_ip_blocklist_ip ON ip_blocklist (ip_address);
CREATE INDEX ix_ip_blocklist_blocked_until ON ip_blocklist (blocked_until);

CREATE TABLE security_events (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    email VARCHAR(255),
    event_type VARCHAR(64) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    details_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_security_events_user_created ON security_events (user_id, created_at DESC);
CREATE INDEX ix_security_events_type_created ON security_events (event_type, created_at DESC);
CREATE INDEX ix_security_events_severity_created ON security_events (severity, created_at DESC);

CREATE TABLE admin_audit_logs (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    admin_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    action VARCHAR(80) NOT NULL,
    target_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    target_ip VARCHAR(64),
    details_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_audit_logs_admin_created ON admin_audit_logs (admin_user_id, created_at DESC);
CREATE INDEX ix_admin_audit_logs_action_created ON admin_audit_logs (action, created_at DESC);

ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS reuse_detected_at TIMESTAMP WITH TIME ZONE;
