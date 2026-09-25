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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C3: add-ons / modifiers — editing them, and how checkout prices and validates them. */
class CatalogModifiersIT extends ApiIT {
    private String ownerA;
    private String ownerB;
    private String customer;
    private String slugA;
    private String slugB;
    private String storeA;
    private String storeB;
    private String burger;   // store A, price 10, stock 20 tracked
    private String fries;    // store A, gets its own add-ons (for "another product's option" checks)
    private String capB;     // store B

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        customer = tokenFor(saveUser(Role.CUSTOMER, "customer"));
        slugA = uniqueSlug("mod-a");
        slugB = uniqueSlug("mod-b");
        storeA = createStore(ownerA, slugA);
        storeB = createStore(ownerB, slugB);
        burger = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "burger", null, null, 20)).andExpect(status().isOk()));
        fries = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "fries", null)).andExpect(status().isOk()));
        capB = idOf(send(POST, "/api/dashboard/products", ownerB, productBody(storeB, "cap", null)).andExpect(status().isOk()));
        activateStore(ownerA, storeA, slugA);
        activateStore(ownerB, storeB, slugB);
    }

    // ── editing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void savingGroupsExposesThemToTheDashboardAndTheStorefront() throws Exception {
        put(burger, ownerA, burgerGroups()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("Size"))
                .andExpect(jsonPath("$.data[0].minSelect").value(1))
                .andExpect(jsonPath("$.data[0].maxSelect").value(1))
                .andExpect(jsonPath("$.data[0].options", hasSize(2)))
                .andExpect(jsonPath("$.data[0].options[0].name").value("Regular"))
                .andExpect(jsonPath("$.data[0].options[0].preselected").value(true))
                .andExpect(jsonPath("$.data[1].name").value("Extras"))
                .andExpect(jsonPath("$.data[1].maxSelect").value(2))
                .andExpect(jsonPath("$.data[1].options[?(@.name=='Avocado')].available").value(false));

        send(GET, "/api/dashboard/products/" + burger, ownerA, null)
                .andExpect(jsonPath("$.data.modifierGroups", hasSize(2)));
        send(GET, "/api/public/stores/" + slugA + "/products/burger", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modifierGroups", hasSize(2)))
                .andExpect(number("$.data.modifierGroups[1].options[?(@.name=='Cheese')].priceDelta", 1.5));
        send(GET, "/api/public/stores/" + slugA + "/products", null, null)
                .andExpect(jsonPath("$.data[?(@.slug=='burger')].modifierGroups.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.slug=='fries')].modifierGroups.length()").value(0));
    }

    @Test
    void reSavingKeepsIdsAndMaxSelectIsCappedAtTheNumberOfOptions() throws Exception {
        ResultActions first = put(burger, ownerA, burgerGroups()).andExpect(status().isOk());
        String extrasId = firstOf(first, "$.data[?(@.name=='Extras')].id");
        String cheeseId = firstOf(first, "$.data[1].options[?(@.name=='Cheese')].id");

        // Rename "Cheese" → "Cheddar" (by id), reprice it, drop the other options and the Size group;
        // ask for max 5 with only one option left → capped to 1.
        String body = "{\"groups\":[{\"id\":\"" + extrasId + "\",\"name\":\"Extras\",\"minSelect\":0,\"maxSelect\":5,\"options\":["
                + "{\"id\":\"" + cheeseId + "\",\"name\":\"Cheddar\",\"priceDelta\":1.75}]}]}";
        put(burger, ownerA, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(extrasId))
                .andExpect(jsonPath("$.data[0].maxSelect").value(1))
                .andExpect(jsonPath("$.data[0].options", hasSize(1)))
                .andExpect(jsonPath("$.data[0].options[0].id").value(cheeseId))
                .andExpect(jsonPath("$.data[0].options[0].name").value("Cheddar"))
                .andExpect(number("$.data[0].options[0].priceDelta", 1.75));

        // No ids at all still lands on the same rows (matched by name).
        put(burger, ownerA, "{\"groups\":[{\"name\":\"extras\",\"options\":[{\"name\":\"cheddar\",\"priceDelta\":2}]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(extrasId))
                .andExpect(jsonPath("$.data[0].options[0].id").value(cheeseId));

        // Empty list removes every add-on.
        put(burger, ownerA, "{\"groups\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void malformedGroupsAreRejected() throws Exception {
        put(burger, ownerA, "{\"groups\":[" + group(null, "A", 0, 1, opt(null, "x", "1")) + "," + group(null, "a", 0, 1, opt(null, "y", "1")) + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("both called")));
        put(burger, ownerA, "{\"groups\":[" + group(null, "A", 0, 1, opt(null, "x", "1"), opt(null, "X", "2")) + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("more than once")));
        // min 3 with only 2 options: can never be satisfied.
        put(burger, ownerA, "{\"groups\":[" + group(null, "A", 3, 3, opt(null, "x", "1"), opt(null, "y", "1")) + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("minimum")));
        // Negative price / no options / too many groups: request validation.
        put(burger, ownerA, "{\"groups\":[" + group(null, "A", 0, 1, opt(null, "x", "-1")) + "]}").andExpect(status().isBadRequest());
        put(burger, ownerA, "{\"groups\":[{\"name\":\"A\",\"options\":[]}]}").andExpect(status().isBadRequest());
        String eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> group(null, "G" + i, 0, 1, opt(null, "x", "1"))).collect(Collectors.joining(","));
        put(burger, ownerA, "{\"groups\":[" + eleven + "]}").andExpect(status().isBadRequest());
        // An id that isn't this product's is "not found".
        put(burger, ownerA, "{\"groups\":[" + group(UUID.randomUUID().toString(), "A", 0, 1, opt(null, "x", "1")) + "]}")
                .andExpect(status().isNotFound());
    }

    // ── checkout ────────────────────────────────────────────────────────────────────────────────

    @Test
    void checkoutAddsTheChosenAddOnsToThePriceAndSnapshotsThem() throws Exception {
        ResultActions saved = put(burger, ownerA, burgerGroups()).andExpect(status().isOk());
        String large = firstOf(saved, "$.data[0].options[?(@.name=='Large')].id");
        String cheese = firstOf(saved, "$.data[1].options[?(@.name=='Cheese')].id");
        String bacon = firstOf(saved, "$.data[1].options[?(@.name=='Bacon')].id");

        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 2, large, cheese, bacon))
                .andExpect(status().isOk())
                // 10 base + 2 (Large) + 1.5 (Cheese) + 2 (Bacon) = 15.5 per unit
                .andExpect(number("$.data.items[0].unitPrice", 15.5))
                .andExpect(number("$.data.items[0].total", 31))
                .andExpect(number("$.data.subtotal", 31))
                .andExpect(jsonPath("$.data.items[0].modifiers", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].modifiers[0].groupName").value("Size"))
                .andExpect(jsonPath("$.data.items[0].modifiers[0].optionName").value("Large"))
                .andExpect(number("$.data.items[0].modifiers[?(@.optionName=='Bacon')].priceDelta", 2));

        // Stock for the line came off the product, as before.
        send(GET, "/api/dashboard/products/" + burger, ownerA, null).andExpect(jsonPath("$.data.stock").value(18));

        // Cancelling that order: status persists, the add-ons are still on the response, stock returns.
        String orderId = firstOf(send(GET, "/api/dashboard/orders?storeId=" + storeA, ownerA, null).andExpect(status().isOk()), "$.data[0].id");
        send(PUT, "/api/dashboard/orders/" + orderId + "/status", ownerA, "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].modifiers", hasSize(3)));
        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        send(GET, "/api/dashboard/products/" + burger, ownerA, null).andExpect(jsonPath("$.data.stock").value(20));
    }

    @Test
    void checkoutEnforcesGroupRulesAvailabilityAndOwnership() throws Exception {
        ResultActions saved = put(burger, ownerA, burgerGroups()).andExpect(status().isOk());
        String regular = firstOf(saved, "$.data[0].options[?(@.name=='Regular')].id");
        String large = firstOf(saved, "$.data[0].options[?(@.name=='Large')].id");
        String cheese = firstOf(saved, "$.data[1].options[?(@.name=='Cheese')].id");
        String bacon = firstOf(saved, "$.data[1].options[?(@.name=='Bacon')].id");
        String avocado = firstOf(saved, "$.data[1].options[?(@.name=='Avocado')].id");

        // Size is required (min 1): nothing chosen → refused, and nothing is picked for the customer.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("Choose at least 1")));
        // Size is pick-one: two sizes refused.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, large))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("Choose at most 1")));
        // Extras are up to 2: three refused (Avocado is also unavailable — count is checked first).
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, cheese, bacon, avocado))
                .andExpect(status().isBadRequest());
        // An unavailable add-on can't be ordered.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, avocado))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unavailable")));
        // The same add-on twice.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, regular))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("twice")));

        // Add-ons that belong to another product (same store) or another store: "not found".
        ResultActions friesSaved = put(fries, ownerA, "{\"groups\":[" + group(null, "Sauce", 0, 1, opt(null, "Ketchup", "0")) + "]}")
                .andExpect(status().isOk());
        String ketchup = firstOf(friesSaved, "$.data[0].options[0].id");
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, ketchup))
                .andExpect(status().isNotFound());
        ResultActions capSaved = put(capB, ownerB, "{\"groups\":[" + group(null, "Fit", 0, 1, opt(null, "Wide", "0")) + "]}")
                .andExpect(status().isOk());
        String wide = firstOf(capSaved, "$.data[0].options[0].id");
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, wide))
                .andExpect(status().isNotFound());
        // …and an add-on of A's product can't ride along on store B's checkout.
        send(POST, "/api/public/stores/" + slugB + "/orders", customer, orderBody(capB, null, 1, cheese))
                .andExpect(status().isNotFound());

        // None of the refused orders touched stock (everything is validated before the decrement).
        send(GET, "/api/dashboard/products/" + burger, ownerA, null).andExpect(jsonPath("$.data.stock").value(20));

        // Control: a valid order still goes through.
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, cheese))
                .andExpect(status().isOk())
                .andExpect(number("$.data.items[0].unitPrice", 11.5));
    }

    @Test
    void addOnsStackOnTopOfAVariantsPriceAndStockComesFromTheVariant() throws Exception {
        ResultActions matrix = send(PUT, "/api/dashboard/products/" + burger + "/variants", ownerA,
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"Single\"},{\"label\":\"Double\"}]}],\"variants\":["
                        + "{\"selection\":[\"Single\"],\"price\":10,\"stock\":5},{\"selection\":[\"Double\"],\"price\":14,\"stock\":5}]}")
                .andExpect(status().isOk());
        String doubleId = firstOf(matrix, "$.data.variants[?(@.label=='Double')].id");
        ResultActions saved = put(burger, ownerA, "{\"groups\":[" + group(null, "Extras", 0, 2,
                opt(null, "Cheese", "1.5"), opt(null, "Bacon", "2")) + "]}").andExpect(status().isOk());
        String bacon = firstOf(saved, "$.data[0].options[?(@.name=='Bacon')].id");

        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, doubleId, 1, bacon))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].variantLabel").value("Double"))
                .andExpect(number("$.data.items[0].unitPrice", 16))
                .andExpect(jsonPath("$.data.items[0].modifiers[0].optionName").value("Bacon"));
        send(GET, "/api/dashboard/products/" + burger + "/variants", ownerA, null)
                .andExpect(jsonPath("$.data.variants[?(@.label=='Double')].stock").value(4))
                .andExpect(jsonPath("$.data.variants[?(@.label=='Single')].stock").value(5));
    }

    @Test
    void anOrdersAddOnsSurviveLaterEditsAndDeletionOfTheCatalogueEntries() throws Exception {
        ResultActions saved = put(burger, ownerA, burgerGroups()).andExpect(status().isOk());
        String regular = firstOf(saved, "$.data[0].options[?(@.name=='Regular')].id");
        String cheese = firstOf(saved, "$.data[1].options[?(@.name=='Cheese')].id");
        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody(burger, null, 1, regular, cheese))
                .andExpect(status().isOk()));

        // Reprice and rename the add-on, then delete every group.
        put(burger, ownerA, "{\"groups\":[" + group(null, "Toppings", 0, 1, opt(null, "Gouda", "9")) + "]}").andExpect(status().isOk());
        put(burger, ownerA, "{\"groups\":[]}").andExpect(status().isOk());

        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null)
                .andExpect(status().isOk())
                .andExpect(number("$.data.items[0].unitPrice", 11.5))
                .andExpect(jsonPath("$.data.items[0].modifiers", hasSize(2)))
                .andExpect(number("$.data.items[0].modifiers[?(@.optionName=='Cheese')].priceDelta", 1.5))
                .andExpect(jsonPath("$.data.items[0].modifiers[?(@.optionName=='Cheese')].groupName").value("Extras"));
    }

    // ── tenant isolation & permissions ──────────────────────────────────────────────────────────

    @Test
    void addOnEndpointsAreIsolatedPerStoreAndHonourTheStaffPermissionGrid() throws Exception {
        put(burger, ownerA, burgerGroups()).andExpect(status().isOk());

        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", ownerB, null).andExpect(status().isForbidden());
        put(burger, ownerB, burgerGroups()).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", null, null).andExpect(status().isUnauthorized());
        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", customer, null).andExpect(status().isForbidden());

        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staff.setStore(storeAEntity);
        staff = userRepository.save(staff);
        String staffToken = tokenFor(staff);
        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", staffToken, null).andExpect(status().isOk());
        put(burger, staffToken, burgerGroups()).andExpect(status().isOk());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", staffToken, null).andExpect(status().isOk());
        put(burger, staffToken, burgerGroups()).andExpect(status().isForbidden());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"NONE\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", staffToken, null).andExpect(status().isForbidden());

        send(GET, "/api/dashboard/products/" + burger + "/modifier-groups", ownerA, null).andExpect(jsonPath("$.data", hasSize(2)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ResultActions put(String productId, String token, String body) throws Exception {
        return send(PUT, "/api/dashboard/products/" + productId + "/modifier-groups", token, body);
    }

    /** Size (required, pick one: Regular free & preselected, Large +2) and Extras (optional, up to 2). */
    private static String burgerGroups() {
        return "{\"groups\":["
                + group(null, "Size", 1, 1, opt(null, "Regular", "0", true, true), opt(null, "Large", "2", false, true)) + ","
                + group(null, "Extras", 0, 2,
                        opt(null, "Cheese", "1.5", false, true), opt(null, "Bacon", "2", false, true), opt(null, "Avocado", "3", false, false))
                + "]}";
    }

    private static String group(String id, String name, int min, int max, String... options) {
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",") + "\"name\":\"" + name + "\",\"minSelect\":" + min
                + ",\"maxSelect\":" + max + ",\"options\":" + Arrays.stream(options).collect(Collectors.joining(",", "[", "]")) + "}";
    }

    private static String opt(String id, String name, String delta) {
        return opt(id, name, delta, false, true);
    }

    private static String opt(String id, String name, String delta, boolean preselected, boolean available) {
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",") + "\"name\":\"" + name + "\",\"priceDelta\":" + delta
                + ",\"preselected\":" + preselected + ",\"available\":" + available + "}";
    }

    private static String orderBody(String productId, String variantId, int quantity, String... modifierIds) {
        String modifiers = modifierIds.length == 0 ? ""
                : ",\"modifierOptionIds\":" + Arrays.stream(modifierIds).map(m -> "\"" + m + "\"").collect(Collectors.joining(",", "[", "]"));
        return "{\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + (variantId == null ? "" : ",\"variantId\":\"" + variantId + "\"") + modifiers + "}]}";
    }

}
