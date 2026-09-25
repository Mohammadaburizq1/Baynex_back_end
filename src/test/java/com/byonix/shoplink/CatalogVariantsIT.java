package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C2: product options → variants, and how checkout treats them. */
class CatalogVariantsIT extends ApiIT {
    private String ownerA;
    private String ownerB;
    private String customer;
    private String slugA;
    private String slugB;
    private String storeA;
    private String storeB;
    private String tee;       // product in store A, will get variants
    private String plain;     // product in store A, stays a simple product
    private String otherB;    // product in store B

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        customer = tokenFor(saveUser(Role.CUSTOMER, "customer"));
        slugA = uniqueSlug("var-a");
        slugB = uniqueSlug("var-b");
        storeA = createStore(ownerA, slugA);
        storeB = createStore(ownerB, slugB);
        tee = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "tee", null, null, null)).andExpect(status().isOk()));
        plain = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "mug", null, null, 10)).andExpect(status().isOk()));
        otherB = idOf(send(POST, "/api/dashboard/products", ownerB, productBody(storeB, "cap", null)).andExpect(status().isOk()));
        activateStore(ownerA, storeA, slugA);
        activateStore(ownerB, storeB, slugB);
    }

    // ── create + derive ─────────────────────────────────────────────────────────────────────────

    @Test
    void savingOptionsAndVariantsDerivesTheProductsFromPriceAndStockAndHidesExactStockFromCustomers() throws Exception {
        put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options", hasSize(1)))
                .andExpect(jsonPath("$.data.options[0].name").value("Size"))
                .andExpect(jsonPath("$.data.options[0].values", hasSize(3)))
                .andExpect(jsonPath("$.data.variants", hasSize(3)))
                .andExpect(jsonPath("$.data.variants[0].label").value("S"))
                .andExpect(jsonPath("$.data.variants[0].selection[0]").value("S"));

        // Merchant view: cheapest available variant's price ("from" pricing), stock summed, exact per variant.
        send(GET, "/api/dashboard/products/" + tee, ownerA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVariants").value(true))
                .andExpect(number("$.data.price", 20.0))
                .andExpect(jsonPath("$.data.stock").value(8))
                .andExpect(jsonPath("$.data.inStock").value(true))
                .andExpect(jsonPath("$.data.variants[?(@.label=='M')].stock").value(3));

        // Customer view: same shape, but availability only — no exact counts anywhere.
        send(GET, "/api/public/stores/" + slugA + "/products/tee", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVariants").value(true))
                .andExpect(jsonPath("$.data.stock").doesNotExist())
                .andExpect(jsonPath("$.data.variants[0].stock").doesNotExist())
                .andExpect(jsonPath("$.data.variants[?(@.label=='L')].inStock").value(false))
                .andExpect(jsonPath("$.data.variants[?(@.label=='S')].inStock").value(true));

        // The storefront list carries the same aggregate (batch-loaded, one query per relation).
        send(GET, "/api/public/stores/" + slugA + "/products", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.slug=='tee')].variants.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.slug=='mug')].hasVariants").value(false));
    }

    @Test
    void aProductWhoseEveryVariantIsSoldOutIsNotInStock() throws Exception {
        put(tee, ownerA, "{\"options\":[" + option("Size", "S", "M") + "],\"variants\":["
                + variant(null, "A-S", "10", null, 0, true, "S") + ","
                + variant(null, "A-M", "10", null, 0, true, "M") + "]}").andExpect(status().isOk());
        send(GET, "/api/public/stores/" + slugA + "/products/tee", null, null)
                .andExpect(jsonPath("$.data.inStock").value(false));
    }

    // ── reconcile ───────────────────────────────────────────────────────────────────────────────

    @Test
    void reSavingKeepsVariantIdsAndNeverOverwritesLiveStock() throws Exception {
        ResultActions first = put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk());
        String sId = firstOf(first, "$.data.variants[?(@.label=='S')].id");
        String mId = firstOf(first, "$.data.variants[?(@.label=='M')].id");
        String sValueId = firstOf(first, "$.data.options[0].values[?(@.label=='S')].id");
        String optionId = read(first, "$.data.options[0].id");

        // A sale of 1 × M takes M's stock from 3 to 2.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, mId, 1)).andExpect(status().isOk());

        // Stale editor: rename S→Small (by value id), reprice M, claim M has stock 99, add XL.
        String body = "{\"options\":[{\"id\":\"" + optionId + "\",\"name\":\"Size\",\"values\":["
                + "{\"id\":\"" + sValueId + "\",\"label\":\"Small\"},{\"label\":\"M\"},{\"label\":\"L\"},{\"label\":\"XL\"}]}],"
                + "\"variants\":["
                + variant(sId, "TEE-S", "20", null, 5, true, "Small") + ","
                + variant(mId, "TEE-M", "30", null, 99, true, "M") + ","
                + variant(null, "TEE-L", "25", "23", 0, true, "L") + ","
                + variant(null, "TEE-XL", "28", null, 7, true, "XL") + "]}";
        ResultActions second = put(tee, ownerA, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants", hasSize(4)));

        second.andExpect(jsonPath("$.data.variants[?(@.label=='Small')].id").value(sId))   // same row, new label
                .andExpect(jsonPath("$.data.variants[?(@.label=='M')].id").value(mId))
                .andExpect(number("$.data.variants[?(@.label=='M')].price", 30))
                .andExpect(jsonPath("$.data.variants[?(@.label=='M')].stock").value(2))     // live count, not the stale 99
                .andExpect(jsonPath("$.data.variants[?(@.label=='XL')].stock").value(7));   // new variant: initial stock honoured

        // Regenerating the matrix without ids still lands on the same rows (matched by combination).
        String noIds = "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"Small\"},{\"label\":\"M\"},{\"label\":\"L\"},{\"label\":\"XL\"}]}],"
                + "\"variants\":["
                + variant(null, "TEE-S", "20", null, null, true, "Small") + ","
                + variant(null, "TEE-M", "30", null, null, true, "M") + ","
                + variant(null, "TEE-L", "25", "23", null, true, "L") + ","
                + variant(null, "TEE-XL", "28", null, null, true, "XL") + "]}";
        put(tee, ownerA, noIds).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].id").value(optionId))
                .andExpect(jsonPath("$.data.variants[?(@.label=='M')].id").value(mId))
                .andExpect(jsonPath("$.data.variants[?(@.label=='M')].stock").value(2));
    }

    @Test
    void removingAVariantOrTheWholeMatrixLeavesOrderHistoryReadable() throws Exception {
        ResultActions saved = put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk());
        String mId = firstOf(saved, "$.data.variants[?(@.label=='M')].id");
        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, mId, 2))
                .andExpect(status().isOk()));

        // Drop M (and L): keep only S.
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, "TEE-S", "20", null, 5, true, "S") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants", hasSize(1)))
                .andExpect(jsonPath("$.data.options[0].values", hasSize(1)));

        // The order still says what was sold, even though the variant row is gone.
        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].variantLabel").value("M"))
                .andExpect(jsonPath("$.data.items[0].sku").value("TEE-M"))
                .andExpect(jsonPath("$.data.items[0].variantId").doesNotExist())
                .andExpect(number("$.data.items[0].unitPrice", 22.0))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        // Cancelling that order must not push stock into the product row (its stock was never touched).
        send(PUT, "/api/dashboard/orders/" + orderId + "/status", ownerA, "{\"status\":\"CANCELLED\"}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + tee, ownerA, null).andExpect(jsonPath("$.data.stock").value(5));

        // Remove everything: the product is a plain product again.
        put(tee, ownerA, "{\"options\":[],\"variants\":[]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options", hasSize(0)))
                .andExpect(jsonPath("$.data.variants", hasSize(0)));
        send(GET, "/api/dashboard/products/" + tee, ownerA, null)
                .andExpect(jsonPath("$.data.hasVariants").value(false))
                .andExpect(jsonPath("$.data.variants", hasSize(0)));
    }

    @Test
    void twoOptionsMakeCombinationsAndDroppingAnOptionCollapsesThem() throws Exception {
        String twoOptions = "{\"options\":[" + option("Size", "S", "M") + "," + option("Color", "Black", "White") + "],\"variants\":["
                + variant(null, "T-S-B", "10", null, 1, true, "S", "Black") + ","
                + variant(null, "T-S-W", "10", null, 1, true, "S", "White") + ","
                + variant(null, "T-M-B", "12", null, 1, true, "M", "Black") + "]}";
        put(tee, ownerA, twoOptions).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants", hasSize(3)))
                .andExpect(jsonPath("$.data.variants[0].label").value("S / Black"))
                .andExpect(jsonPath("$.data.variants[?(@.label=='M / Black')].selection[0]").value("M"));

        // Drop the Color option: variants must be re-listed against the remaining option.
        put(tee, ownerA, "{\"options\":[" + option("Size", "S", "M") + "],\"variants\":["
                + variant(null, "T-S", "10", null, 1, true, "S") + ","
                + variant(null, "T-M", "12", null, 1, true, "M") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options", hasSize(1)))
                .andExpect(jsonPath("$.data.variants[*].label", contains("S", "M")));
    }

    // ── checkout ────────────────────────────────────────────────────────────────────────────────

    @Test
    void checkoutSellsFromTheChosenVariantAndCancellationPutsTheStockBack() throws Exception {
        ResultActions saved = put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk());
        String sId = firstOf(saved, "$.data.variants[?(@.label=='S')].id");
        String lId = firstOf(saved, "$.data.variants[?(@.label=='L')].id");

        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, null, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("choose an option")));

        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, sId, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].variantLabel").value("S"))
                .andExpect(jsonPath("$.data.items[0].sku").value("TEE-S"))
                .andExpect(number("$.data.items[0].unitPrice", 20.0))
                .andExpect(number("$.data.subtotal", 40.0)));
        variantsOfTee().andExpect(jsonPath("$.data.variants[?(@.label=='S')].stock").value(3));

        // More than what's left: refused, stock untouched.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, sId, 4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Insufficient stock")));
        variantsOfTee().andExpect(jsonPath("$.data.variants[?(@.label=='S')].stock").value(3));

        // A sold-out variant can't be bought either.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, lId, 1))
                .andExpect(status().isBadRequest());

        // A variant on a product that has none, or from a different product, or that doesn't exist.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(plain, sId, 1)).andExpect(status().isBadRequest());
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, UUID.randomUUID().toString(), 1))
                .andExpect(status().isNotFound());

        // Cancelling persists the new status (not just the stock) and puts the units back on the variant.
        send(PUT, "/api/dashboard/orders/" + orderId + "/status", ownerA, "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].variantLabel").value("S"));
        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        variantsOfTee().andExpect(jsonPath("$.data.variants[?(@.label=='S')].stock").value(5));
    }

    @Test
    void aSnapshotOfTheSaleSurvivesLaterPriceChanges() throws Exception {
        ResultActions saved = put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk());
        String mId = firstOf(saved, "$.data.variants[?(@.label=='M')].id");
        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, mId, 1))
                .andExpect(status().isOk()));

        put(tee, ownerA, "{\"options\":[" + option("Size", "S", "M", "L") + "],\"variants\":["
                + variant(null, "TEE-S", "20", null, null, true, "S") + ","
                + variant(null, "TEE-M", "99", null, null, true, "M") + ","
                + variant(null, "TEE-L", "25", null, null, true, "L") + "]}").andExpect(status().isOk());

        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null)
                .andExpect(number("$.data.items[0].unitPrice", 22.0))
                .andExpect(number("$.data.total", 22.0));
    }

    @Test
    void anUnavailableVariantCannotBeOrdered() throws Exception {
        ResultActions saved = put(tee, ownerA, "{\"options\":[" + option("Size", "S", "M") + "],\"variants\":["
                + variant(null, "X-S", "10", null, 5, true, "S") + ","
                + variant(null, "X-M", "10", null, 5, false, "M") + "]}").andExpect(status().isOk());
        String mId = firstOf(saved, "$.data.variants[?(@.label=='M')].id");
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(tee, mId, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("unavailable")));
    }

    // ── products with variants have no stock of their own ───────────────────────────────────────

    @Test
    void editingTheProductCannotSetStockOnceItHasVariants() throws Exception {
        put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk());
        send(PUT, "/api/dashboard/products/" + tee, ownerA, productBody(storeA, "tee", null, null, 999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(8));
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────

    @Test
    void malformedMatricesAreRejectedWithSpecificMessages() throws Exception {
        String v = variant(null, "V1", "10", null, 1, true, "S");

        put(tee, ownerA, "{\"options\":[],\"variants\":[" + v + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("at least one option")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("at least one variant")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "," + option("size", "M") + "],\"variants\":["
                + variant(null, null, "10", null, 1, true, "S", "M") + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("both called")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S", "s") + "],\"variants\":[" + v + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("more than once")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, null, "10", null, 1, true, "XXL") + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("isn't a value")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, "A", "10", null, 1, true, "S") + "," + variant(null, "B", "10", null, 1, true, "S") + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("same combination")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, null, "10", "10", 1, true, "S") + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("sale price")));
        put(tee, ownerA, "{\"options\":[" + option("Size", "S", "M") + "],\"variants\":["
                + variant(null, "dup", "10", null, 1, true, "S") + "," + variant(null, "DUP", "10", null, 1, true, "M") + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("more than one variant")));
        // Too many options is caught by request validation.
        put(tee, ownerA, "{\"options\":[" + option("A", "1") + "," + option("B", "1") + "," + option("C", "1") + "," + option("D", "1")
                + "],\"variants\":[" + v + "]}").andExpect(status().isBadRequest());
        // An option id that isn't this product's is "not found".
        put(tee, ownerA, "{\"options\":[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Size\",\"values\":[{\"label\":\"S\"}]}],\"variants\":[" + v + "]}")
                .andExpect(status().isNotFound());
    }

    @Test
    void skusShareOneNamespacePerStoreAcrossProductsAndVariants() throws Exception {
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "hat", null, "HAT-1", null)).andExpect(status().isOk());

        // A variant can't take a SKU another product already holds…
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, "hat-1", "10", null, 1, true, "S") + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already used")));

        // …and once a variant holds one, no product (new or existing) can take it.
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, "TEE-S", "10", null, 1, true, "S") + "]}").andExpect(status().isOk());
        send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "tee-2", null, "tee-s", null)).andExpect(status().isConflict());
        // Another store is a different namespace.
        send(POST, "/api/dashboard/products", ownerB, productBody(storeB, "tee-b", null, "TEE-S", null)).andExpect(status().isOk());
        // Re-saving the same product keeps its own variant SKUs without tripping over itself.
        put(tee, ownerA, "{\"options\":[" + option("Size", "S") + "],\"variants\":["
                + variant(null, "TEE-S", "11", null, 1, true, "S") + "]}").andExpect(status().isOk());
    }

    // ── tenant isolation & permissions ──────────────────────────────────────────────────────────

    @Test
    void variantEndpointsAreIsolatedPerStoreAndHonourTheStaffPermissionGrid() throws Exception {
        ResultActions saved = put(tee, ownerA, sizeAndPrices()).andExpect(status().isOk());
        String sId = firstOf(saved, "$.data.variants[?(@.label=='S')].id");

        // Another merchant: no read, no write.
        send(GET, "/api/dashboard/products/" + tee + "/variants", ownerB, null).andExpect(status().isForbidden());
        put(tee, ownerB, sizeAndPrices()).andExpect(status().isForbidden());

        // Anonymous and customers never reach the dashboard.
        send(GET, "/api/dashboard/products/" + tee + "/variants", null, null).andExpect(status().isUnauthorized());
        send(GET, "/api/dashboard/products/" + tee + "/variants", customer, null).andExpect(status().isForbidden());

        // Checkout injection: B's storefront can't sell A's product or A's variant.
        send(POST, "/api/public/stores/" + slugB + "/orders", customer, orderBody(tee, sId, 1)).andExpect(status().isNotFound());
        send(POST, "/api/public/stores/" + slugB + "/orders", customer, orderBody(otherB, sId, 1)).andExpect(status().isBadRequest());

        // Staff of store A: default grid = EDIT; VIEW can read but not write.
        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staff.setStore(storeAEntity);
        staff = userRepository.save(staff);
        String staffToken = tokenFor(staff);
        send(GET, "/api/dashboard/products/" + tee + "/variants", staffToken, null).andExpect(status().isOk());
        put(tee, staffToken, sizeAndPrices()).andExpect(status().isOk());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + tee + "/variants", staffToken, null).andExpect(status().isOk());
        put(tee, staffToken, sizeAndPrices()).andExpect(status().isForbidden());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"NONE\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + tee + "/variants", staffToken, null).andExpect(status().isForbidden());

        // A's data is untouched by all of that.
        send(GET, "/api/dashboard/products/" + tee + "/variants", ownerA, null)
                .andExpect(jsonPath("$.data.variants", hasSize(3)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ResultActions put(String productId, String token, String body) throws Exception {
        return send(PUT, "/api/dashboard/products/" + productId + "/variants", token, body);
    }

    private ResultActions variantsOfTee() throws Exception {
        return send(GET, "/api/dashboard/products/" + tee + "/variants", ownerA, null).andExpect(status().isOk());
    }

    private String sizeAndPrices() {
        return "{\"options\":[" + option("Size", "S", "M", "L") + "],\"variants\":["
                + variant(null, "TEE-S", "20", null, 5, true, "S") + ","
                + variant(null, "TEE-M", "22", null, 3, true, "M") + ","
                + variant(null, "TEE-L", "25", "23", 0, true, "L") + "]}";
    }

    private static String option(String name, String... labels) {
        String values = Arrays.stream(labels).map(l -> "{\"label\":\"" + l + "\"}").collect(Collectors.joining(",", "[", "]"));
        return "{\"name\":\"" + name + "\",\"values\":" + values + "}";
    }

    private static String variant(String id, String sku, String price, String salePrice, Integer stock, boolean available,
                                  String... selection) {
        StringBuilder sb = new StringBuilder("{");
        if (id != null) {
            sb.append("\"id\":\"").append(id).append("\",");
        }
        sb.append("\"selection\":").append(Arrays.stream(selection).map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]")));
        if (sku != null) {
            sb.append(",\"sku\":\"").append(sku).append('"');
        }
        sb.append(",\"price\":").append(price);
        if (salePrice != null) {
            sb.append(",\"salePrice\":").append(salePrice);
        }
        if (stock != null) {
            sb.append(",\"stock\":").append(stock);
        }
        return sb.append(",\"available\":").append(available).append('}').toString();
    }

    private static String orderBody(String productId, String variantId, int quantity) {
        return "{\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + (variantId == null ? "" : ",\"variantId\":\"" + variantId + "\"") + "}]}";
    }

}
