package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.Store;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** M1-13 regression: the template a merchant picks must be the one the storefront renders. */
class MapperServiceTemplateKeyTest {
    private final MapperService mapper = new MapperService();

    private static Store store(String category, String sub, String templateKey) {
        Store s = new Store();
        s.setCategorySlug(category);
        s.setSubCategorySlug(sub);
        s.setTemplateKey(templateKey);
        return s;
    }

    @Test
    void chosenTemplateWinsOverTheCoarseRestaurantSubCategory() {
        // What onboarding sends for "Ramen Night" / any coffee design.
        assertEquals("ramen-shop", mapper.effectiveTemplateKey(store("restaurants-cafes", "fast-food", "ramen-shop")));
        assertEquals("coffee-neon-drip", mapper.effectiveTemplateKey(store("restaurants-cafes", "cafe", "coffee-neon-drip")));
    }

    @Test
    void legacyRestaurantRowsResolveAsBefore() {
        assertEquals("burger-restaurant", mapper.effectiveTemplateKey(store("restaurants-cafes", "burger-restaurant", "burger-restaurant")));
        assertEquals("cafe", mapper.effectiveTemplateKey(store("restaurants-cafes", "cafe", null)));
        assertEquals("restaurant-default", mapper.effectiveTemplateKey(store("restaurants-cafes", null, null)));
    }

    @Test
    void otherCategoriesUseTheStoredKey() {
        assertEquals("real-estate-agency", mapper.effectiveTemplateKey(store("general-store", null, "real-estate-agency")));
        assertNull(mapper.effectiveTemplateKey(store("general-store", null, null)));
    }
}
