package com.byonix.shoplink;

import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C1: category integrity plus SKU / slug uniqueness within a store. */
class CatalogCategoriesSkuIT extends ApiIT {
    private String ownerA;
    private String ownerB;
    private String slugA;
    private String storeA;
    private String storeB;

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        slugA = uniqueSlug("cat-a");
        storeA = createStore(ownerA, slugA);
        storeB = createStore(ownerB, uniqueSlug("cat-b"));
    }

    @Test
    void skuMustBeUniquePerStoreCaseInsensitivelyAndSelfIsExcludedOnUpdate() throws Exception {
        String first = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "shirt", null, "TSHIRT-01", null))
                .andExpect(status().isOk()));

        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "shirt-2", null, "tshirt-01", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("SKU")))
                .andExpect(jsonPath("$.message", containsString("tshirt-01")));

        // Per store: another merchant may use the same SKU.
        send(POST, "/api/dashboard/products", ownerB, productBody(storeB, "shirt", null, "TSHIRT-01", null))
                .andExpect(status().isOk());

        // Re-saving a product with its own SKU is not a conflict with itself.
        send(PUT, "/api/dashboard/products/" + first, ownerA, productBody(storeA, "shirt", null, "TSHIRT-01", 5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").value("TSHIRT-01"));

        // Blank SKUs mean "none" — any number of products may leave it empty, and it's stored as null.
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "no-sku-1", null, "", null)).andExpect(status().isOk());
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "no-sku-2", null, "   ", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").doesNotExist());
    }

    @Test
    void duplicateProductSlugIsAReadableConflictButOnlyWithinTheStore() throws Exception {
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "burger", null)).andExpect(status().isOk());
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "burger", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("URL slug")));
        send(POST, "/api/dashboard/products", ownerB, productBody(storeB, "burger", null)).andExpect(status().isOk());
    }

    @Test
    void duplicateCategorySlugIsAReadableConflictButOnlyWithinTheStore() throws Exception {
        send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "drinks", null)).andExpect(status().isOk());
        send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "drinks", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("URL slug")));
        send(POST, "/api/dashboard/categories", ownerB, categoryBody(storeB, "drinks", null)).andExpect(status().isOk());

        // Renaming another category to a taken slug is refused too; keeping its own slug is fine.
        String other = idOf(send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "food", null)).andExpect(status().isOk()));
        send(PUT, "/api/dashboard/categories/" + other, ownerA, categoryBody(storeA, "drinks", null)).andExpect(status().isConflict());
        send(PUT, "/api/dashboard/categories/" + other, ownerA, categoryBody(storeA, "food", null)).andExpect(status().isOk());
    }

    @Test
    void aCategoryCannotBeMovedUnderItselfOrAnyOfItsDescendants() throws Exception {
        String a = idOf(send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "a", null)).andExpect(status().isOk()));
        String b = idOf(send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "b", a)).andExpect(status().isOk()));
        String c = idOf(send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "c", b)).andExpect(status().isOk()));

        send(PUT, "/api/dashboard/categories/" + a, ownerA, categoryBody(storeA, "a", a)).andExpect(status().isBadRequest());
        send(PUT, "/api/dashboard/categories/" + a, ownerA, categoryBody(storeA, "a", c))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("under itself")));

        // Control: re-parenting sideways is fine.
        send(PUT, "/api/dashboard/categories/" + c, ownerA, categoryBody(storeA, "c", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(a));
    }

    @Test
    void productsKeepTheirRealCategoryAndOnlyActiveCategoriesAreListedPublicly() throws Exception {
        String drinks = idOf(send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "drinks", null)).andExpect(status().isOk()));
        send(POST, "/api/dashboard/categories", ownerA, categoryBody(storeA, "hidden", null, false)).andExpect(status().isOk());
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "cola", drinks))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(drinks));
        activateStore(ownerA, storeA, slugA);

        send(GET, "/api/public/stores/" + slugA + "/categories", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", hasItem("drinks")))
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("hidden"))));
        send(GET, "/api/public/stores/" + slugA + "/products/cola", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(drinks));
    }

    @Test
    void aCategoryFromAnotherStoreCannotBeUsedOnAProduct() throws Exception {
        String foreign = idOf(send(POST, "/api/dashboard/categories", ownerB, categoryBody(storeB, "theirs", null)).andExpect(status().isOk()));
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "sneaky", foreign)).andExpect(status().isForbidden());
    }
}
