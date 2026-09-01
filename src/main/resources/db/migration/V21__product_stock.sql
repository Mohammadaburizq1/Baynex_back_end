-- Nullable, no default — same convention as sale_price: NULL means "not applicable / not
-- tracked for this product" rather than a false "zero". A SERVICE product (real estate listing,
-- appointment booking) never gets a stock value at all; a PRODUCT/FOOD_ITEM merchant who hasn't
-- set one yet also reads as "not tracked" rather than a misleading 0-in-stock.
ALTER TABLE products ADD COLUMN stock INTEGER CHECK (stock IS NULL OR stock >= 0);
