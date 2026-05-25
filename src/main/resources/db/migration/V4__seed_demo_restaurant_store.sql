INSERT INTO app_users (id, full_name, email, password_hash, phone, role, is_active, token_version)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Demo Merchant',
    'demo@shoplink.local',
    '$2a$12$S8Z8w36Q9rriC3JGqymv4eTy3GCfGTdhU6i0WaPNjoAKrP2/PGz0C',
    '+962790000000',
    'MERCHANT',
    TRUE,
    0
);

INSERT INTO stores (id, owner_id, name, slug, description, phone, whatsapp_number, address, city, country, category_slug, sub_category_slug, template_key, status, primary_color, secondary_color)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000001',
    'Foodie Restaurant',
    'foodie-demo',
    'Fresh favorite dishes in Amman.',
    '+962790000000',
    '+962790000000',
    'Amman, Jordan',
    'Amman',
    'Jordan',
    'restaurants-cafes',
    'chinese-restaurant',
    'restaurant-default',
    'ACTIVE',
    '#d84f2a',
    '#1f7a5c'
);

INSERT INTO categories (id, store_id, name_en, slug, sort_order, is_active, category_type)
VALUES ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000101', 'Popular Dishes', 'popular-dishes', 10, TRUE, 'PRODUCT');

INSERT INTO products (store_id, category_id, name_en, slug, description, price, currency, product_type, is_available, is_featured, sort_order) VALUES
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Salmon Salad', 'salmon-salad', 'Salmon with crisp greens.', 6.500, 'JOD', 'FOOD_ITEM', TRUE, TRUE, 10),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Spaghetti Pasta', 'spaghetti-pasta', 'Classic spaghetti pasta.', 5.000, 'JOD', 'FOOD_ITEM', TRUE, TRUE, 20),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Vegetable Salad', 'vegetable-salad', 'Seasonal vegetables and herbs.', 3.500, 'JOD', 'FOOD_ITEM', TRUE, FALSE, 30),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Chicken Noodles', 'chicken-noodles', 'Stir-fried noodles with chicken.', 4.750, 'JOD', 'FOOD_ITEM', TRUE, TRUE, 40),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Butter Chicken', 'butter-chicken', 'Creamy butter chicken.', 6.000, 'JOD', 'FOOD_ITEM', TRUE, TRUE, 50),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Burger Meal', 'burger-meal', 'Burger, fries, and drink.', 5.250, 'JOD', 'FOOD_ITEM', TRUE, FALSE, 60),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Orange Chicken', 'orange-chicken', 'Citrus glazed chicken.', 5.750, 'JOD', 'FOOD_ITEM', TRUE, TRUE, 70),
('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000201', 'Beef Lo Mein', 'beef-lo-mein', 'Noodles with tender beef.', 6.250, 'JOD', 'FOOD_ITEM', TRUE, TRUE, 80);
