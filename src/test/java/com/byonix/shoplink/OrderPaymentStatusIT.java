package com.byonix.shoplink;

import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** paymentStatus: independent of paymentMethod and of fulfillment status (see PaymentStatus.java). */
class OrderPaymentStatusIT extends ApiIT {
    private String ownerA;
    private String ownerB;
    private String customer;
    private String slugA;
    private String storeA;
    private String mug;

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        customer = tokenFor(saveUser(Role.CUSTOMER, "customer"));
        slugA = uniqueSlug("pay-a");
        storeA = createStore(ownerA, slugA);
        mug = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "mug", null)).andExpect(status().isOk()));
        activateStore(ownerA, storeA, slugA);
    }

    private String orderBody(String paymentMethod) {
        return "{\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"" + paymentMethod + "\",\"items\":[{\"productId\":\"" + mug + "\",\"quantity\":1}]}";
    }

    @Test
    void cashOrderStartsUnpaidAndCardOrderStartsPending() throws Exception {
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody("CASH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("UNPAID"));
        send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody("CARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));
    }

    @Test
    void staffCanMarkAnOrderPaidThenRefunded() throws Exception {
        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody("CASH"))
                .andExpect(status().isOk()));

        send(PUT, "/api/dashboard/orders/" + orderId + "/payment-status", ownerA, "{\"status\":\"PAID\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"));
        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null)
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"));

        // Payment status is independent of fulfillment: cancelling the order does not touch it.
        send(PUT, "/api/dashboard/orders/" + orderId + "/status", ownerA, "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk());
        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null)
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"));

        send(PUT, "/api/dashboard/orders/" + orderId + "/payment-status", ownerA, "{\"status\":\"REFUNDED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"));

        // Terminal: no further change, even back to the same idea of "unpaid".
        send(PUT, "/api/dashboard/orders/" + orderId + "/payment-status", ownerA, "{\"status\":\"UNPAID\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotRefundAnOrderThatWasNeverMarkedPaid() throws Exception {
        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody("CASH"))
                .andExpect(status().isOk()));
        send(PUT, "/api/dashboard/orders/" + orderId + "/payment-status", ownerA, "{\"status\":\"REFUNDED\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void foreignOwnerCannotChangeAnotherStoresOrderPaymentStatus() throws Exception {
        String orderId = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, orderBody("CASH"))
                .andExpect(status().isOk()));
        send(PUT, "/api/dashboard/orders/" + orderId + "/payment-status", ownerB, "{\"status\":\"PAID\"}")
                .andExpect(status().isForbidden());
        send(GET, "/api/dashboard/orders/" + orderId, ownerA, null)
                .andExpect(jsonPath("$.data.paymentStatus").value("UNPAID"));
    }
}
