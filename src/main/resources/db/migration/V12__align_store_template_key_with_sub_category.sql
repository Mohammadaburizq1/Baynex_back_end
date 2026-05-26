-- sub_category_slug and template_key should match for restaurant styles.
UPDATE stores
SET template_key = sub_category_slug
WHERE sub_category_slug IS NOT NULL
  AND btrim(sub_category_slug) <> ''
  AND (template_key IS NULL OR template_key <> sub_category_slug);
