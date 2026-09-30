-- Bulk product import (Excel/CSV): one row per upload that was previewed.
--
-- The spreadsheet itself is NOT stored: the merchant's browser sends the same file again on confirm
-- and the server re-parses and re-validates it (backend authoritative), accepting it only if its
-- SHA-256 matches what was previewed. The row is the import's audit record (who, which store, which
-- file, what happened) and its double-submit guard: confirm moves PREVIEWED -> IMPORTING with a
-- conditional UPDATE, so a second click cannot run the import twice.
CREATE TABLE product_import_sessions (
    id                 UUID PRIMARY KEY,
    store_id           UUID         NOT NULL REFERENCES stores (id) ON DELETE CASCADE,
    user_id            UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    file_name          VARCHAR(255) NOT NULL,
    file_sha256        VARCHAR(64)  NOT NULL,
    images_file_name   VARCHAR(255),
    images_sha256      VARCHAR(64),
    existing_strategy  VARCHAR(20)  NOT NULL,
    mode               VARCHAR(20)  NOT NULL,
    create_categories  BOOLEAN      NOT NULL DEFAULT FALSE,
    status             VARCHAR(20)  NOT NULL,
    total_rows         INTEGER      NOT NULL DEFAULT 0,
    created_count      INTEGER      NOT NULL DEFAULT 0,
    updated_count      INTEGER      NOT NULL DEFAULT 0,
    skipped_count      INTEGER      NOT NULL DEFAULT 0,
    failed_count       INTEGER      NOT NULL DEFAULT 0,
    images_attached    INTEGER      NOT NULL DEFAULT 0,
    image_failures     INTEGER      NOT NULL DEFAULT 0,
    -- The per-row report (preview, then the result) as JSON: row numbers, identifiers, codes and
    -- messages only; never the whole spreadsheet.
    report_json        TEXT,
    expires_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_product_import_status CHECK (status IN ('PREVIEWED', 'IMPORTING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_product_import_existing CHECK (existing_strategy IN ('SKIP', 'UPDATE', 'FAIL')),
    CONSTRAINT ck_product_import_mode CHECK (mode IN ('VALID_ROWS_ONLY', 'ALL_OR_NOTHING'))
);

CREATE INDEX idx_product_import_sessions_store ON product_import_sessions (store_id, created_at DESC);
