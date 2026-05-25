-- =============================================================================
-- ShopLink: create or update an admin user (run in pgAdmin → shoplink_db)
-- =============================================================================
-- 1. Connect to database: shoplink_db
-- 2. Edit the four values in the DECLARE block below
-- 3. Execute the whole script (F5)
--
-- Requires extension pgcrypto (created by Flyway V1).
-- Password hashing uses bcrypt cost 12 (compatible with the Java backend).
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
DECLARE
    -- >>> EDIT THESE <<<
    v_email       TEXT := 'admin@yourcompany.com';
    v_full_name   TEXT := 'ShopLink Admin';
    v_password    TEXT := 'ChangeMe!12345';
    v_role        TEXT := 'SUPER_ADMIN';  -- SUPER_ADMIN | SUPPORT_ADMIN | FINANCE_ADMIN | READ_ONLY_ADMIN

    v_hash        TEXT;
    v_user_id     UUID;
BEGIN
    v_email := lower(trim(v_email));

    IF v_role NOT IN ('SUPER_ADMIN', 'SUPPORT_ADMIN', 'FINANCE_ADMIN', 'READ_ONLY_ADMIN') THEN
        RAISE EXCEPTION 'Invalid role: %. Use an admin role only.', v_role;
    END IF;

    IF length(v_password) < 12 THEN
        RAISE EXCEPTION 'Use a password with at least 12 characters.';
    END IF;

    v_hash := crypt(v_password, gen_salt('bf', 12));

    SELECT id INTO v_user_id
    FROM app_users
    WHERE lower(email) = v_email
    LIMIT 1;

    IF v_user_id IS NOT NULL THEN
        UPDATE app_users
        SET full_name              = v_full_name,
            password_hash          = v_hash,
            role                   = v_role,
            is_active              = TRUE,
            email_verified_at      = COALESCE(email_verified_at, NOW()),
            password_changed_at    = NOW(),
            failed_login_count     = 0,
            locked_until           = NULL,
            force_password_reset   = FALSE,
            admin_unlock_required  = FALSE,
            suspicious_activity_flag = FALSE,
            mfa_enabled            = FALSE,
            updated_at             = NOW()
        WHERE id = v_user_id;

        RAISE NOTICE 'Updated admin user: % (role=%)', v_email, v_role;
    ELSE
        INSERT INTO app_users (
            id,
            full_name,
            email,
            password_hash,
            role,
            is_active,
            token_version,
            email_verified_at,
            failed_login_count,
            mfa_enabled,
            force_password_reset,
            suspicious_activity_flag,
            admin_unlock_required,
            password_changed_at,
            created_at,
            updated_at
        ) VALUES (
            gen_random_uuid(),
            v_full_name,
            v_email,
            v_hash,
            v_role,
            TRUE,
            0,
            NOW(),
            0,
            FALSE,
            FALSE,
            FALSE,
            FALSE,
            NOW(),
            NOW(),
            NOW()
        );

        RAISE NOTICE 'Created admin user: % (role=%)', v_email, v_role;
    END IF;
END $$;

-- =============================================================================
-- VERIFY (run after the block above — password_ok MUST be true)
-- =============================================================================
-- SELECT
--     email,
--     role,
--     is_active,
--     locked_until,
--     force_password_reset,
--     crypt('ChangeMe!12345', password_hash) = password_hash AS password_ok
-- FROM app_users
-- WHERE lower(email) = lower('admin@yourcompany.com');
--
-- If password_ok is false → password in Flutter does not match v_password above.
-- If no row → user is in a different database than the backend (Azure vs local Docker).
-- Backend on localhost:8081 (Docker) uses DB shoplink_db on localhost:5432, NOT Azure,
-- unless you changed DB_HOST in docker-compose / env.
