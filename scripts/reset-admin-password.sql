-- Reset admin password (pgAdmin → same database your backend uses)
-- Edit email + password, run entire script, then check password_ok = true.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
DECLARE
    v_email    TEXT := 'admin@shoplink.app';   -- must match Flutter login email exactly
    v_password TEXT := 'Admin@123456789';      -- min 12 chars; must match Flutter login
    v_hash     TEXT;
BEGIN
    v_email := lower(trim(v_email));
    v_hash := crypt(v_password, gen_salt('bf', 12));

    UPDATE app_users
    SET password_hash         = v_hash,
        failed_login_count    = 0,
        locked_until          = NULL,
        force_password_reset  = FALSE,
        is_active             = TRUE,
        updated_at            = NOW()
    WHERE lower(email) = v_email;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No user with email %', v_email;
    END IF;
END $$;

SELECT
    email,
    role,
    crypt('Admin@123456789', password_hash) = password_hash AS password_ok
FROM app_users
WHERE lower(email) = lower('admin@shoplink.app');
