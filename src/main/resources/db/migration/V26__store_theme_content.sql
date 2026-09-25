CREATE TABLE store_theme_content (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL UNIQUE REFERENCES stores(id) ON DELETE CASCADE,
    draft_content JSONB NOT NULL DEFAULT '{}'::jsonb,
    published_content JSONB,
    published_version INTEGER NOT NULL DEFAULT 0,
    draft_updated_at TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE store_theme_content_versions (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    content JSONB NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (store_id, version)
);
CREATE INDEX ix_store_theme_content_versions_store_id ON store_theme_content_versions(store_id);
