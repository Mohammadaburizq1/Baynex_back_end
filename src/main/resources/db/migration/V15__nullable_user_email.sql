-- Phone-only merchants do not require an email address.
ALTER TABLE app_users ALTER COLUMN email DROP NOT NULL;

DROP INDEX IF EXISTS ux_app_users_email_lower;

${emailLowerIndexV15}
