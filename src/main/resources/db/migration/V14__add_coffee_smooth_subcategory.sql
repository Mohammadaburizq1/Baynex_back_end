-- Coffee Smooth — Peet's-style storefront; `coffee-beans` keeps Chamberlain-style UI.
INSERT INTO categories (parent_id, name_en, slug, sort_order, is_active, category_type)
SELECT p.id, 'Coffee Smooth', 'coffee-smooth', 46, TRUE, 'BUSINESS'
FROM categories p
WHERE p.slug = 'restaurants-cafes'
  AND NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE c.parent_id = p.id AND c.slug = 'coffee-smooth'
  );
