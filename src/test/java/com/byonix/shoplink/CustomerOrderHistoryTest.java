package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.*;
import com.byonix.shoplink.domain.enums.*;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real JWT/security/controller/service/repository integration, including anonymous checkout.
 * V33's PostgreSQL multi-column ALTER is not H2-compatible. Isolate these behavioral tests
 * with an entity-generated schema; the SQL fixture supplies the non-entity sales table.
 * This deliberately does not claim to validate the production Flyway migrations.
 */
class CustomerOrderHistoryTest extends ApiIT {
    private static final String HISTORY = "/api/public/customers/me/orders";
    private User a, b;
    private String tokenA, tokenB, merchant, slug, storeId, productId;

    @BeforeEach
    void fixtures() throws Exception {
        a = saveUser(Role.CUSTOMER, "history-a");
        a.setPhone("+962790000000");
        b = saveUser(Role.CUSTOMER, "history-b");
        tokenA = tokenFor(a); tokenB = tokenFor(b);
        merchant = tokenFor(saveUser(Role.MERCHANT_OWNER, "history-merchant"));
        slug = uniqueSlug("history");
        storeId = createStore(merchant, slug);
        productId = idOf(send(POST, "/api/dashboard/products", merchant, productBody(storeId, "original", null)).andExpect(status().isOk()));
        activateStore(merchant, storeId, slug);
    }

    private String place(String token) throws Exception {
        return idOf(send(POST, "/api/public/stores/" + slug + "/orders", token,
                """
                {"customerName":"History customer","customerEmail":"%s","customerPhone":"+962790000000",
                 "deliveryMethod":"PICKUP","paymentMethod":"CASH","items":[{"productId":"%s","quantity":2}]}
                """.formatted(a.getEmail(), productId)).andExpect(status().isOk()));
    }

    @Test
    void ownHistoryIsNewestFirstAndBrowserIdentityCannotOverridePrincipal() throws Exception {
        String older = place(tokenA);
        CustomerOrder old = orderRepository.findById(UUID.fromString(older)).orElseThrow();
        old.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        String newer = place(tokenA);
        String foreign = place(tokenB);
        String guest = place(null);
        send(GET, HISTORY + "?customerId=" + b.getId() + "&userId=" + b.getId() + "&email=" + b.getEmail(), tokenA, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(newer))
                .andExpect(jsonPath("$.data[1].id").value(older));
        send(GET, HISTORY + "/" + newer, tokenA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productNameSnapshot").value("Item original"))
                .andExpect(number("$.data.total", 20))
                .andExpect(jsonPath("$.data.customer.passwordHash").doesNotExist());
        send(GET, HISTORY + "/" + foreign, tokenA, null).andExpect(status().isNotFound());
        send(GET, HISTORY + "/" + newer, tokenB, null).andExpect(status().isNotFound());
        send(GET, HISTORY + "/" + guest, tokenA, null).andExpect(status().isNotFound());
        assertNull(orderRepository.findById(UUID.fromString(guest)).orElseThrow().getCustomer());
        send(GET, HISTORY + "?page=0&size=1", tokenA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(newer));
        send(GET, HISTORY + "?page=1&size=1", tokenA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(older));
        send(GET, HISTORY + "?page=2&size=1", tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        for (String query : new String[]{"?page=-1", "?size=0", "?size=101"}) {
            send(GET, HISTORY + query, tokenA, null).andExpect(status().isBadRequest());
        }
    }

    @Test
    void anonymousMerchantAndAdminCannotReadHistoryOrDetail() throws Exception {
        String id = place(tokenA);
        for (String path : new String[]{HISTORY, HISTORY + "/" + id}) {
            send(GET, path, null, null).andExpect(status().isUnauthorized());
            send(GET, path, merchant, null).andExpect(status().isForbidden());
            for (Role role : new Role[]{Role.SUPER_ADMIN, Role.SUPPORT_ADMIN, Role.FINANCE_ADMIN, Role.READ_ONLY_ADMIN, Role.MERCHANT_STAFF}) {
                send(GET, path, tokenFor(saveUser(role, "other-role")), null).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void emptyHistoryAndMatchingGuestRemainUnownedAndTrackingStillRequiresVerification() throws Exception {
        String guest = place(null);
        send(GET, HISTORY, tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        CustomerOrder order = orderRepository.findById(UUID.fromString(guest)).orElseThrow();
        assertNull(order.getCustomer());
        String code = order.getOrderCode();
        send(POST, "/api/public/stores/" + slug + "/orders/lookup", null,
                "{\"orderCode\":\"" + code + "\",\"email\":\"" + a.getEmail() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderCode").value(code))
                .andExpect(jsonPath("$.data.id").doesNotExist());
        send(POST, "/api/public/stores/" + slug + "/orders/lookup", null,
                "{\"orderCode\":\"" + code + "\",\"email\":\"wrong@test.com\"}").andExpect(status().isNotFound());
        String laterEmail = "later-" + UUID.randomUUID() + "@example.test";
        orderRepository.findById(UUID.fromString(guest)).orElseThrow().setCustomerEmail(laterEmail);
        String laterToken = read(send(POST, "/api/public/auth/register", null,
                """
                {"fullName":"History customer","email":"%s","phone":"+962790000000","password":"History-test!42"}
                """.formatted(laterEmail)).andExpect(status().isOk()), "$.data.accessToken");
        send(GET, HISTORY, laterToken, null).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        assertNull(orderRepository.findById(UUID.fromString(guest)).orElseThrow().getCustomer());
    }

    @Test
    void historicalCurrencyFulfillmentFeeAndItemsSurviveCurrentConfigurationChanges() throws Exception {
        Store store = storeRepository.findById(UUID.fromString(storeId)).orElseThrow();
        store.setCurrency("JOD");
        DeliveryZone zone = new DeliveryZone(); zone.setStore(store); zone.setName("Changed zone");
        zone.setDeliveryFee(new BigDecimal("2.125")); entityManager.persist(zone);
        String id = idOf(send(POST, "/api/public/stores/" + slug + "/orders", tokenA,
                """
                {"customerName":"History customer","customerPhone":"+962790000000","customerAddress":"Historical address",
                 "deliveryMethod":"DELIVERY","deliveryZoneId":"%s","paymentMethod":"CASH",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(zone.getId(), productId)).andExpect(status().isOk()));
        CustomerOrder order = orderRepository.findById(UUID.fromString(id)).orElseThrow();
        store = order.getStore(); store.setCurrency("USD"); store.setPickupAvailable(false);
        entityManager.find(DeliveryZone.class, zone.getId()).setDeliveryFee(new BigDecimal("5"));
        Product product = productRepository.findById(UUID.fromString(productId)).orElseThrow();
        product.setNameEn("Renamed product"); product.setPrice(new BigDecimal("99"));
        for (String path : new String[]{HISTORY, HISTORY + "/" + id}) {
            String root = path.equals(HISTORY) ? "$.data[0]" : "$.data";
            send(GET, path, tokenA, null).andExpect(status().isOk())
                    .andExpect(jsonPath(root + ".currency").value("JOD"))
                    .andExpect(jsonPath(root + ".deliveryMethod").value("DELIVERY"))
                    .andExpect(number(root + ".deliveryFee", 2.125))
                    .andExpect(number(root + ".total", 22.125))
                    .andExpect(jsonPath(root + ".items[0].productNameSnapshot").value("Item original"))
                    .andExpect(number(root + ".items[0].unitPrice", 10));
        }
    }

    @Test
    void legacyNullCurrencyStaysNullOnListAndDetail() throws Exception {
        String id = place(tokenA);
        CustomerOrder order = orderRepository.findById(UUID.fromString(id)).orElseThrow();
        order.setCurrency(null); order.getStore().setCurrency("USD");
        // Global NON_NULL serialization omits unknown currency rather than inventing a value.
        send(GET, HISTORY, tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].currency").doesNotExist());
        send(GET, HISTORY + "/" + id, tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.currency").doesNotExist());
    }
}
