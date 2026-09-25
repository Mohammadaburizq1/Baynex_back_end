-- An ordered gallery per product. The first image (lowest sort_order) is the product's primary
-- image; the service keeps products.image_url — what storefront templates already read — pointing
-- at it, so nothing that consumes image_url has to change.
CREATE TABLE product_images (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    url VARCHAR(500) NOT NULL,
    alt_text VARCHAR(200),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_product_images_product_id ON product_images(product_id);
CREATE INDEX ix_product_images_store_id ON product_images(store_id);

-- Every product that already had a single image keeps it as its first gallery image.
INSERT INTO product_images (store_id, product_id, url, sort_order)
SELECT store_id, id, image_url, 0
FROM products
WHERE image_url IS NOT NULL AND image_url <> '';
