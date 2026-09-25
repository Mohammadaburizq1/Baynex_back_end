package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C4: stock counts, the ledger behind them, adjustments and low-stock alerts. */
class InventoryIT extends ApiIT {
    private String ownerA;
    private String ownerB;
    private String customer;
    private String slugA;
    private String storeA;
    private String storeB;
    private String mug;      // plain, tracked, opening stock 10
    private String poster;   // plain, untracked (no stock)
    private String tee;      // variants S=5, M=3, L=0
    private String sId;
    private String mId;
    private String lId;
    private String consultation; // a SERVICE

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        customer = tokenFor(saveUser(Role.CUSTOMER, "customer"));
        slugA = uniqueSlug("inv-a");
        storeA = createStore(ownerA, slugA);
        storeB = createStore(ownerB, uniqueSlug("inv-b"));
        mug = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "mug", null, "MUG-1", 10)).andExpect(status().isOk()));
        poster = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "poster", null)).andExpect(status().isOk()));
        consultation = idOf(send(POST, "/api/dashboard/products", ownerA,
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Consult\",\"slug\":\"consult\",\"price\":30,\"sortOrder\":0,\"productType\":\"SERVICE\"}")
                .andExpect(status().isOk()));
        tee = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "tee", null)).andExpect(status().isOk()));
        ResultActions matrix = send(PUT, "/api/dashboard/products/" + tee + "/variants", ownerA,
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"},{\"label\":\"L\"}]}],\"variants\":["
                        + "{\"selection\":[\"S\"],\"sku\":\"TEE-S\",\"price\":20,\"stock\":5},"
                        + "{\"selection\":[\"M\"],\"sku\":\"TEE-M\",\"price\":22,\"stock\":3},"
                        + "{\"selection\":[\"L\"],\"sku\":\"TEE-L\",\"price\":25,\"stock\":0}]}").andExpect(status().isOk());
        sId = firstOf(matrix, "$.data.variants[?(@.label=='S')].id");
        mId = firstOf(matrix, "$.data.variants[?(@.label=='M')].id");
        lId = firstOf(matrix, "$.data.variants[?(@.label=='L')].id");
        activateStore(ownerA, storeA, slugA);
    }

    // ── the opening count ───────────────────────────────────────────────────────────────────────

    @Test
    void anOpeningCountStartsTheHistoryAndUntrackedItemsHaveNone() throws Exception {
        history(ownerA, "").andExpect(status().isOk())
                // mug (10), tee S (5), tee M (3) — not the untracked poster, the service, or the zero-stock L.
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug')].reason").value("INITIAL"))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug')].delta").value(10))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug')].stockAfter").value(10))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item tee (S)')].stockAfter").value(5))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item tee (M)')].stockAfter").value(3));
    }

    @Test
    void editingAProductCanSetItsThresholdButNeverItsStock() throws Exception {
        send(PUT, "/api/dashboard/products/" + mug, ownerA,
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Item mug\",\"slug\":\"mug\",\"price\":10,\"sortOrder\":0,\"sku\":\"MUG-1\",\"stock\":999,\"lowStockThreshold\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.lowStockThreshold").value(2));
        // The storefront never sees the threshold either.
        send(GET, "/api/public/stores/" + slugA + "/products/mug", null, null)
                .andExpect(jsonPath("$.data.lowStockThreshold").doesNotExist());
    }

    // ── adjustments ─────────────────────────────────────────────────────────────────────────────

    @Test
    void manualAdjustmentsSetOrAddAndAreRecordedWithTheirReason() throws Exception {
        adjust(mug, null, "DELTA", -3, "DAMAGED", "dropped a box").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(7))
                .andExpect(jsonPath("$.data.status").value("OK"));
        adjust(mug, null, "SET", 20, "RESTOCK", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(20));
        send(GET, "/api/dashboard/products/" + mug, ownerA, null).andExpect(jsonPath("$.data.stock").value(20));

        history(ownerA, "&productId=" + mug).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].reason").value("RESTOCK"))
                .andExpect(jsonPath("$.data[0].delta").value(13))       // 7 → 20
                .andExpect(jsonPath("$.data[0].stockAfter").value(20))
                .andExpect(jsonPath("$.data[1].reason").value("DAMAGED"))
                .andExpect(jsonPath("$.data[1].delta").value(-3))
                .andExpect(jsonPath("$.data[1].note").value("dropped a box"))
                .andExpect(jsonPath("$.data[2].reason").value("INITIAL"));
    }

    @Test
    void adjustmentsAreRefusedWhenTheyMakeNoSense() throws Exception {
        adjust(mug, null, "DELTA", -11, "CORRECTION", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("below zero")));
        adjust(mug, null, "DELTA", 0, "CORRECTION", null).andExpect(status().isBadRequest());
        adjust(mug, null, "SET", -1, "CORRECTION", null).andExpect(status().isBadRequest());
        // System reasons can't be claimed by a merchant.
        adjust(mug, null, "DELTA", 1, "ORDER_PLACED", null).andExpect(status().isBadRequest());
        adjust(mug, null, "DELTA", 1, "INITIAL", null).andExpect(status().isBadRequest());
        // Nothing counted yet → a relative change has no baseline.
        adjust(poster, null, "DELTA", 5, "RESTOCK", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("starting count")));
        // A service has no units.
        adjust(consultation, null, "SET", 5, "CORRECTION", null).andExpect(status().isBadRequest());
        // A product sold through variants is adjusted per variant.
        adjust(tee, null, "SET", 5, "CORRECTION", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("variant")));
        // A variant id on a plain product / from a different product / unknown → not found.
        adjust(mug, sId, "SET", 5, "CORRECTION", null).andExpect(status().isNotFound());
        adjust(tee, UUID.randomUUID().toString(), "SET", 5, "CORRECTION", null).andExpect(status().isNotFound());

        // None of that changed anything.
        send(GET, "/api/dashboard/products/" + mug, ownerA, null).andExpect(jsonPath("$.data.stock").value(10));
        history(ownerA, "&productId=" + mug).andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void settingACountOnAnUntrackedItemStartsTrackingIt() throws Exception {
        adjust(poster, null, "SET", 4, "CORRECTION", "counted the shelf").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(4))
                .andExpect(jsonPath("$.data.status").value("LOW"));
        history(ownerA, "&productId=" + poster)
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].delta").value(4))
                .andExpect(jsonPath("$.data[0].stockAfter").value(4));
    }

    @Test
    void variantsAreAdjustedIndividuallyAndTheProductTotalFollows() throws Exception {
        adjust(tee, lId, "SET", 6, "RESTOCK", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variantLabel").value("L"))
                .andExpect(jsonPath("$.data.stock").value(6));
        send(GET, "/api/dashboard/products/" + tee, ownerA, null)
                .andExpect(jsonPath("$.data.stock").value(14))            // 5 + 3 + 6
                .andExpect(jsonPath("$.data.inStock").value(true));
        history(ownerA, "&variantId=" + lId)
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].itemName").value("Item tee (L)"));
    }

    // ── sales and cancellations ─────────────────────────────────────────────────────────────────

    @Test
    void salesAndCancellationsWriteToTheLedgerForEveryLineEvenWithADiscountCode() throws Exception {
        send(POST, "/api/dashboard/offers", ownerA,
                "{\"storeId\":\"" + storeA + "\",\"code\":\"SAVE10\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10}")
                .andExpect(status().isOk());
        // Two lines in one order: a plain product and a variant — both must be recorded (the second
        // stock UPDATE must not swallow the first line's ledger row), and so must the offer usage.
        String body = "{\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"discountCode\":\"SAVE10\",\"items\":["
                + "{\"productId\":\"" + mug + "\",\"quantity\":2},"
                + "{\"productId\":\"" + tee + "\",\"quantity\":1,\"variantId\":\"" + sId + "\"}]}";
        ResultActions placed = send(POST, "/api/public/stores/" + slugA + "/orders", customer, body).andExpect(status().isOk());
        String orderId = idOf(placed);
        String orderCode = read(placed, "$.data.orderCode");

        history(ownerA, "").andExpect(jsonPath("$.data[?(@.reason=='ORDER_PLACED')]", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug' && @.reason=='ORDER_PLACED')].delta").value(-2))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug' && @.reason=='ORDER_PLACED')].stockAfter").value(8))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug' && @.reason=='ORDER_PLACED')].reference").value(orderCode))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item tee (S)' && @.reason=='ORDER_PLACED')].stockAfter").value(4))
                // Customers are identified by the order code, not by name.
                .andExpect(jsonPath("$.data[?(@.reason=='ORDER_PLACED')].createdBy").isEmpty());

        send(PUT, "/api/dashboard/orders/" + orderId + "/status", ownerA, "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        history(ownerA, "").andExpect(jsonPath("$.data[?(@.reason=='ORDER_CANCELLED')]", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug' && @.reason=='ORDER_CANCELLED')].delta").value(2))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug' && @.reason=='ORDER_CANCELLED')].stockAfter").value(10))
                .andExpect(jsonPath("$.data[?(@.itemName=='Item mug' && @.reason=='ORDER_CANCELLED')].reference").value(orderCode));
        send(GET, "/api/dashboard/products/" + mug, ownerA, null).andExpect(jsonPath("$.data.stock").value(10));

        // Cancelling again must not restore twice.
        send(PUT, "/api/dashboard/orders/" + orderId + "/status", ownerA, "{\"status\":\"CANCELLED\"}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + mug, ownerA, null).andExpect(jsonPath("$.data.stock").value(10));
        history(ownerA, "").andExpect(jsonPath("$.data[?(@.reason=='ORDER_CANCELLED')]", hasSize(2)));
    }

    @Test
    void anUntrackedProductLeavesNoLedgerRowsWhenSold() throws Exception {
        String body = "{\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + poster + "\",\"quantity\":3}]}";
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, body).andExpect(status().isOk());
        history(ownerA, "&productId=" + poster).andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── alerts & the inventory list ─────────────────────────────────────────────────────────────

    @Test
    void alertsListLowAndOutItemsEmptiestFirstUsingTheirOwnThresholds() throws Exception {
        // Defaults (5): tee S=5 LOW, tee M=3 LOW, tee L=0 OUT. mug=10 is fine; poster is untracked.
        send(GET, "/api/dashboard/inventory/alerts?storeId=" + storeA, ownerA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lowCount").value(2))
                .andExpect(jsonPath("$.data.outCount").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].variantLabel").value("L"))
                .andExpect(jsonPath("$.data.items[0].status").value("OUT"))
                .andExpect(jsonPath("$.data.items[1].variantLabel").value("M"))
                .andExpect(jsonPath("$.data.items[2].variantLabel").value("S"));

        // mug with its own threshold of 12 becomes LOW at 10; the tee's S variant with a threshold of 4 stops being LOW at 5.
        send(PUT, "/api/dashboard/products/" + mug, ownerA,
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Item mug\",\"slug\":\"mug\",\"price\":10,\"sortOrder\":0,\"sku\":\"MUG-1\",\"lowStockThreshold\":12}")
                .andExpect(status().isOk());
        send(PUT, "/api/dashboard/products/" + tee + "/variants", ownerA,
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"},{\"label\":\"L\"}]}],\"variants\":["
                        + "{\"selection\":[\"S\"],\"sku\":\"TEE-S\",\"price\":20,\"lowStockThreshold\":4},"
                        + "{\"selection\":[\"M\"],\"sku\":\"TEE-M\",\"price\":22},"
                        + "{\"selection\":[\"L\"],\"sku\":\"TEE-L\",\"price\":25}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/inventory/alerts?storeId=" + storeA, ownerA, null)
                .andExpect(jsonPath("$.data.items[?(@.name=='Item mug')].status").value("LOW"))
                .andExpect(jsonPath("$.data.items[?(@.name=='Item mug')].effectiveThreshold").value(12))
                .andExpect(jsonPath("$.data.items[?(@.variantLabel=='S')]", hasSize(0)))
                .andExpect(jsonPath("$.data.lowCount").value(2))   // mug + M
                .andExpect(jsonPath("$.data.outCount").value(1));

        // Restocking clears the alert.
        adjust(mug, null, "SET", 50, "RESTOCK", null).andExpect(status().isOk());
        send(GET, "/api/dashboard/inventory/alerts?storeId=" + storeA, ownerA, null)
                .andExpect(jsonPath("$.data.items[?(@.name=='Item mug')]", hasSize(0)));
    }

    @Test
    void theInventoryListCoversEveryStockableItemAndSkipsServices() throws Exception {
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, ownerA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(5)))    // mug, poster, tee S/M/L — not the service
                .andExpect(jsonPath("$.data[?(@.name=='Item poster')].status").value("UNTRACKED"))
                .andExpect(jsonPath("$.data[?(@.name=='Item mug')].status").value("OK"))
                .andExpect(jsonPath("$.data[?(@.name=='Item mug')].sku").value("MUG-1"))
                .andExpect(jsonPath("$.data[?(@.name=='Item tee' && @.variantLabel=='L')].status").value("OUT"))
                .andExpect(jsonPath("$.data[?(@.name=='Consult')]", hasSize(0)));
    }

    // ── tenant isolation & permissions ──────────────────────────────────────────────────────────

    @Test
    void inventoryIsIsolatedPerStoreAndHonoursTheStaffPermissionGrid() throws Exception {
        // Another merchant: nothing readable or changeable.
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, ownerB, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/inventory/alerts?storeId=" + storeA, ownerB, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/inventory/history?storeId=" + storeA, ownerB, null).andExpect(status().isForbidden());
        adjustAs(ownerB, mug, null, "SET", 999, "CORRECTION").andExpect(status().isForbidden());
        adjustAs(ownerB, tee, sId, "SET", 999, "CORRECTION").andExpect(status().isForbidden());

        // Asking store B's history about A's product finds nothing (every query is store-scoped).
        send(GET, "/api/dashboard/inventory/history?storeId=" + storeB + "&productId=" + mug, ownerB, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));

        // Anonymous / customer.
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, null, null).andExpect(status().isUnauthorized());
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, customer, null).andExpect(status().isForbidden());

        // Staff of store A: default = EDIT; VIEW reads but can't adjust; NONE sees nothing.
        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staff.setStore(storeAEntity);
        staff = userRepository.save(staff);
        String staffToken = tokenFor(staff);
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, staffToken, null).andExpect(status().isOk());
        adjustAs(staffToken, mug, null, "DELTA", 1, "RESTOCK").andExpect(status().isOk());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, staffToken, null).andExpect(status().isOk());
        send(GET, "/api/dashboard/inventory/alerts?storeId=" + storeA, staffToken, null).andExpect(status().isOk());
        adjustAs(staffToken, mug, null, "DELTA", 1, "RESTOCK").andExpect(status().isForbidden());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"NONE\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/inventory?storeId=" + storeA, staffToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/inventory/alerts?storeId=" + storeA, staffToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/inventory/history?storeId=" + storeA, staffToken, null).andExpect(status().isForbidden());

        // Only the one permitted +1 ever landed: 10 → 11, one extra history row.
        send(GET, "/api/dashboard/products/" + mug, ownerA, null).andExpect(jsonPath("$.data.stock").value(11));
        history(ownerA, "&productId=" + mug).andExpect(jsonPath("$.data", hasSize(2)));
        send(GET, "/api/dashboard/products/" + tee + "/variants", ownerA, null)
                .andExpect(jsonPath("$.data.variants[?(@.label=='S')].stock").value(5));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ResultActions history(String token, String extraQuery) throws Exception {
        return send(GET, "/api/dashboard/inventory/history?storeId=" + storeA + extraQuery, token, null);
    }

    private ResultActions adjust(String productId, String variantId, String mode, int quantity, String reason, String note) throws Exception {
        return adjustAs(ownerA, productId, variantId, mode, quantity, reason, note);
    }

    private ResultActions adjustAs(String token, String productId, String variantId, String mode, int quantity, String reason) throws Exception {
        return adjustAs(token, productId, variantId, mode, quantity, reason, null);
    }

    private ResultActions adjustAs(String token, String productId, String variantId, String mode, int quantity, String reason,
                                   String note) throws Exception {
        String body = "{\"productId\":\"" + productId + "\","
                + (variantId == null ? "" : "\"variantId\":\"" + variantId + "\",")
                + "\"mode\":\"" + mode + "\",\"quantity\":" + quantity + ",\"reason\":\"" + reason + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        return send(POST, "/api/dashboard/inventory/adjust", token, body);
    }
}
