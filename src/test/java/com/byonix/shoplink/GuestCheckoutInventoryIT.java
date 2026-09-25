package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.InventoryAdjustment;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.InventoryAdjustmentRepository;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1-13 regression: a guest (anonymous) order for a stock-tracked product used to fail with 403,
 * because the inventory ledger demanded an authenticated actor for the SALE row. Same
 * entity-generated H2 schema as CustomerOrderHistoryTest (V33 can't run on H2).
 */


class GuestCheckoutInventoryIT extends ApiIT {
    @Autowired InventoryAdjustmentRepository adjustments;

    private String owner;
    private String slug;
    private String storeId;
    private String burger;

    @BeforeEach
    void setUp() throws Exception {
        owner = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner"));
        slug = uniqueSlug("guest-stock");
        storeId = createStore(owner, slug);
        burger = idOf(send(POST, "/api/dashboard/products", owner, productBody(storeId, "burger", null, null, 5)).andExpect(status().isOk()));
        activateStore(owner, storeId, slug);
    }

    private String order(int quantity) {
        return "{\"customerName\":\"Guest\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + burger + "\",\"quantity\":" + quantity + "}]}";
    }

    @Test
    void guestCanBuyAStockTrackedProductAndTheSaleIsLedgeredWithoutAnActor() throws Exception {
        send(POST, "/api/public/stores/" + slug + "/orders", null, order(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderCode").isNotEmpty());

        send(GET, "/api/dashboard/products/" + burger, owner, null).andExpect(jsonPath("$.data.stock").value(3));
        List<InventoryAdjustment> rows = adjustments.findRecentByProduct(UUID.fromString(storeId), UUID.fromString(burger), PageRequest.of(0, 10));
        InventoryAdjustment sale = rows.get(0);
        assertEquals(-2, sale.getDelta());
        assertEquals(3, sale.getStockAfter());
        assertNull(sale.getCreatedBy());
    }

    @Test
    void guestOrderBeyondStockIsRejectedWithoutSideEffects() throws Exception {
        send(POST, "/api/public/stores/" + slug + "/orders", null, order(6)).andExpect(status().is4xxClientError());
        send(GET, "/api/dashboard/products/" + burger, owner, null).andExpect(jsonPath("$.data.stock").value(5));
    }
}
