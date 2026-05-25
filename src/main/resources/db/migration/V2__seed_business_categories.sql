INSERT INTO categories (name_en, slug, sort_order, is_active, category_type) VALUES
('Restaurants & Cafes', 'restaurants-cafes', 10, TRUE, 'BUSINESS'),
('Cleaning Services', 'cleaning-services', 20, TRUE, 'BUSINESS'),
('Sweets & Bakery', 'sweets-bakery', 30, TRUE, 'BUSINESS'),
('Clothes & Fashion', 'clothes-fashion', 40, TRUE, 'BUSINESS'),
('Beauty & Salon', 'beauty-salon', 50, TRUE, 'BUSINESS'),
('Gifts & Flowers', 'gifts-flowers', 60, TRUE, 'BUSINESS'),
('General Store', 'general-store', 70, TRUE, 'BUSINESS');

INSERT INTO categories (parent_id, name_en, slug, sort_order, is_active, category_type)
SELECT p.id, v.name_en, v.slug, v.sort_order, TRUE, 'BUSINESS'
FROM categories p
CROSS JOIN (VALUES
    ('Chinese Restaurant', 'chinese-restaurant', 10),
    ('Pizza Restaurant', 'pizza-restaurant', 20),
    ('Burger Restaurant', 'burger-restaurant', 30),
    ('Cafe', 'cafe', 40),
    ('Dessert Shop', 'dessert-shop', 50),
    ('Fast Food', 'fast-food', 60),
    ('Healthy Food', 'healthy-food', 70),
    ('Seafood Restaurant', 'seafood-restaurant', 80),
    ('Breakfast Restaurant', 'breakfast-restaurant', 90)
) AS v(name_en, slug, sort_order)
WHERE p.slug = 'restaurants-cafes';
