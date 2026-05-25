#!/usr/bin/env bash
# Creates or updates a ShopLink admin user in PostgreSQL.
#
# Usage:
#   ./scripts/create-admin-user.sh --email admin@example.com --password 'ChangeMe!12345'
#   ./scripts/create-admin-user.sh --email admin@example.com --password 'x' --role SUPPORT_ADMIN --docker
#
set -euo pipefail

EMAIL=""
PASSWORD=""
FULL_NAME="ShopLink Admin"
ROLE="SUPER_ADMIN"
FORCE=0
USE_DOCKER=0
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-shoplink_db}"
DB_USER="${DB_USERNAME:-shoplink_user}"
DB_PASSWORD="${DB_PASSWORD:-}"

usage() {
  sed -n '2,8p' "$0"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --email) EMAIL="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --full-name) FULL_NAME="$2"; shift 2 ;;
    --role) ROLE="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --docker) USE_DOCKER=1; shift ;;
    --db-host) DB_HOST="$2"; shift 2 ;;
    --db-name) DB_NAME="$2"; shift 2 ;;
    --db-user) DB_USER="$2"; shift 2 ;;
    --db-password) DB_PASSWORD="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

[[ -n "$EMAIL" && -n "$PASSWORD" ]] || usage

if [[ -z "$DB_PASSWORD" ]]; then
  read -r -s -p "Database password ($DB_USER@$DB_HOST): " DB_PASSWORD
  echo
fi

EMAIL_NORM=$(echo "$EMAIL" | tr '[:upper:]' '[:lower:]' | xargs)
FULL_NAME_ESC=${FULL_NAME//\'/\'\'}
EMAIL_ESC=${EMAIL_NORM//\'/\'\'}

echo "Generating BCrypt hash (cost 12)..."
PWD_B64=$(printf '%s' "$PASSWORD" | base64 | tr -d '\n')
HASH=$(docker run --rm -e "PWD_B64=$PWD_B64" node:22-alpine sh -c '
  apk add --no-cache python3 py3-pip >/dev/null 2>&1
  pip install --quiet bcrypt
  python3 -c "
import os, base64, bcrypt
pwd = base64.b64decode(os.environ[\"PWD_B64\"]).decode(\"utf-8\")
print(bcrypt.hashpw(pwd.encode(\"utf-8\"), bcrypt.gensalt(rounds=12)).decode(\"utf-8\"))
"
')
HASH_ESC=${HASH//\'/\'\'}

SQL_FILE=$(mktemp)
trap 'rm -f "$SQL_FILE"' EXIT

FORCE_SQL=$([[ "$FORCE" -eq 1 ]] && echo true || echo false)

cat >"$SQL_FILE" <<EOF
DO \$\$
DECLARE
    v_email TEXT := '$EMAIL_ESC';
    v_exists UUID;
BEGIN
    SELECT id INTO v_exists FROM app_users WHERE lower(email) = lower(v_email) LIMIT 1;

    IF v_exists IS NOT NULL AND NOT $FORCE_SQL THEN
        RAISE EXCEPTION 'User already exists: %. Re-run with --force to update.', v_email;
    END IF;

    IF v_exists IS NOT NULL THEN
        UPDATE app_users SET
            full_name = '$FULL_NAME_ESC',
            password_hash = '$HASH_ESC',
            role = '$ROLE',
            is_active = TRUE,
            email_verified_at = COALESCE(email_verified_at, NOW()),
            password_changed_at = NOW(),
            failed_login_count = 0,
            locked_until = NULL,
            force_password_reset = FALSE,
            admin_unlock_required = FALSE,
            mfa_enabled = FALSE,
            updated_at = NOW()
        WHERE id = v_exists;
        RAISE NOTICE 'Updated admin user % (role=$ROLE)', v_email;
    ELSE
        INSERT INTO app_users (
            id, full_name, email, password_hash, role, is_active, token_version,
            email_verified_at, failed_login_count, mfa_enabled, force_password_reset,
            suspicious_activity_flag, admin_unlock_required, password_changed_at,
            created_at, updated_at
        ) VALUES (
            gen_random_uuid(), '$FULL_NAME_ESC', v_email, '$HASH_ESC', '$ROLE', TRUE, 0,
            NOW(), 0, FALSE, FALSE, FALSE, FALSE, NOW(), NOW(), NOW()
        );
        RAISE NOTICE 'Created admin user % (role=$ROLE)', v_email;
    END IF;
END \$\$;
EOF

if [[ "$USE_DOCKER" -eq 1 ]]; then
  echo "Running SQL via docker compose..."
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <"$SQL_FILE"
else
  echo "Running SQL via psql..."
  PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$SQL_FILE"
fi

echo ""
echo "Done."
echo "  Email: $EMAIL_NORM"
echo "  Role:  $ROLE"
echo "  Login: POST /api/admin/auth/login"
