-- =============================================================================
-- Azure PostgreSQL: create ShopLink database (run manually in pgAdmin)
-- =============================================================================
-- I cannot run this against your Azure server from here — paste each step into
-- pgAdmin Query Tool while connected as your Azure server admin.
--
-- STEP A — Connect to database "postgres" (or "azure_maintenance" if required
-- by your Azure tier). Then execute only the block below.
-- =============================================================================

CREATE DATABASE shoplink_db
    ENCODING 'UTF8';

-- If you get "already exists", the database is there — skip to STEP B.

-- =============================================================================
-- STEP B — In pgAdmin: connect to server → Databases → shoplink_db → Query Tool.
-- Replace REPLACE_WITH_STRONG_PASSWORD before running.
-- =============================================================================

CREATE ROLE shoplink_user WITH LOGIN PASSWORD 'REPLACE_WITH_STRONG_PASSWORD';

GRANT CONNECT ON DATABASE shoplink_db TO shoplink_user;
GRANT USAGE ON SCHEMA public TO shoplink_user;
GRANT CREATE ON SCHEMA public TO shoplink_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO shoplink_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO shoplink_user;

-- =============================================================================
-- STEP C — Point the Java app at Azure (env vars or hosting panel):
--   DB_HOST=<your-server>.postgres.database.azure.com
--   DB_PORT=5432
--   DB_NAME=shoplink_db
--   DB_USERNAME=shoplink_user
--   DB_PASSWORD=<same as Step B>
--   DB_URL_SUFFIX=?sslmode=require
-- Firewall: allow your machine / app outbound IPs on the Azure server.
-- Flyway will create tables on first application startup.
-- =============================================================================
