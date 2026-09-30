package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-26 restaurant mode over real HTTP (MockMvc + full security chain) on PostgreSQL.
 * Not @Transactional: idempotency depends on real commits and real unique keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
class PosRestaurantIT {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;

    private MockMvc mockMvc;
    private String remoteAddr;
    private User owner;
    private User cashier;
    private String ownerA;
    private String ownerB;
    private String storeA;
    private String storeB;
    private String burger, coke, dessert, fries;
    private String mainHall;
    private String t1, t2;

    @BeforeEach
    void setUp() throws Exception {
        Filter chain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(chain).build();
        int n = COUNTER.incrementAndGet();
        remoteAddr = "10.93." + (n / 250) + "." + (n % 250 + 1);
        owner = saveUser(Role.MERCHANT_OWNER, "Olivia Owner", null);
        ownerA = jwtService.createAccessToken(owner);
        ownerB = jwtService.createAccessToken(saveUser(Role.MERCHANT_OWNER, "Other Owner", null));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        storeA = idOf(send(POST, "/api/dashboard/stores", bearer(ownerA), "{\"name\":\"Table Bistro\",\"slug\":\"rest-a-" + suffix
                + "\",\"categorySlug\":\"restaurants-cafes\",\"templateKey\":\"restaurant-default\",\"currency\":\"JOD\",\"timezone\":\"Asia/Amman\"}")
                .andExpect(status().isOk()));
        cashier = saveUser(Role.MERCHANT_STAFF, "Walt Waiter", storeA);
        burger = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), product("Burger", "burger", "5.000", 10, 0)).andExpect(status().isOk()));
        send(PUT, "/api/dashboard/products/" + burger + "/modifier-groups", bearer(ownerA), "{\"groups\":[{\"name\":\"Cheese\",\"minSelect\":0,"
                + "\"maxSelect\":1,\"options\":[{\"name\":\"Extra cheese\",\"priceDelta\":0.5,\"preselected\":false,\"available\":true}]},"
                + "{\"name\":\"Cooking\",\"minSelect\":1,\"maxSelect\":1,\"options\":[{\"name\":\"Medium\",\"priceDelta\":0,\"preselected\":true,\"available\":true},"
                + "{\"name\":\"Well done\",\"priceDelta\":0,\"preselected\":false,\"available\":true}]}]}").andExpect(status().isOk());
        coke = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), product("Coke", "coke", "1.000", 20, 1)).andExpect(status().isOk()));
        dessert = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), product("Dessert", "dessert", "3.000", null, 2)).andExpect(status().isOk()));
        fries = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), product("Fries", "fries", "2.000", 10, 3)).andExpect(status().isOk()));
        mainHall = idOf(send(POST, "/api/dashboard/restaurant/areas?storeId=" + storeA, bearer(ownerA), "{\"name\":\"Main Hall\"}").andExpect(status().isOk()));
        t1 = idOf(send(POST, "/api/dashboard/restaurant/tables?storeId=" + storeA, bearer(ownerA),
                "{\"areaId\":\"" + mainHall + "\",\"name\":\"T1\",\"capacity\":4}").andExpect(status().isOk()));
        t2 = idOf(send(POST, "/api/dashboard/restaurant/tables?storeId=" + storeA, bearer(ownerA),
                "{\"areaId\":\"" + mainHall + "\",\"name\":\"T2\",\"capacity\":2}").andExpect(status().isOk()));
        storeB = idOf(send(POST, "/api/dashboard/stores", bearer(ownerB),
                "{\"name\":\"Other\",\"slug\":\"rest-b-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\"}").andExpect(status().isOk()));
    }

    // ── setup ────────────────────────────────────────────────────────────────────────────────

    @Test
    void restaurantModeFollowsTheBusinessTypeUnlessSetAndTablesAreStoreScoped() throws Exception {
        send(GET, "/api/dashboard/restaurant/settings?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effective").value(true)).andExpect(jsonPath("$.data.byBusinessType").value(true));
        send(GET, "/api/dashboard/restaurant/settings?storeId=" + storeB, bearer(ownerB), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effective").value(false));
        send(PUT, "/api/dashboard/restaurant/settings?storeId=" + storeB, bearer(ownerB), "{\"restaurantMode\":true}")
                .andExpect(jsonPath("$.data.effective").value(true));
        Device a = activate(storeA, ownerA, "Waiter tablet");
        String setup = send(GET, "/api/pos/restaurant/setup", a.auth(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantMode").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(setup, "$.data.tables[*].name")).containsExactly("T1", "T2");
        assertThat(JsonPath.<List<String>>read(setup, "$.data.areas[*].name")).containsExactly("Main Hall");
        // Duplicate active names are refused; store B cannot read or change store A's setup.
        send(POST, "/api/dashboard/restaurant/tables?storeId=" + storeA, bearer(ownerA), "{\"areaId\":\"" + mainHall + "\",\"name\":\"t1\"}")
                .andExpect(status().isConflict());
        send(GET, "/api/dashboard/restaurant/tables?storeId=" + storeA, bearer(ownerB), null).andExpect(status().is4xxClientError());
        send(PUT, "/api/dashboard/restaurant/tables/" + t1, bearer(ownerB), "{\"areaId\":\"" + mainHall + "\",\"name\":\"Mine\"}")
                .andExpect(status().is4xxClientError());
        Device other = activate(storeB, ownerB, "Other till");
        String otherSetup = send(GET, "/api/pos/restaurant/setup", other.auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(otherSetup, "$.data.tables")).isEmpty();
    }

    // ── golden path ──────────────────────────────────────────────────────────────────────────

    @Test
    void dineInGoldenPathOpensAddsOverTimeMovesSplitsPaysCashAndTerminalAndClosesTheTable() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        String version = catalogVersion(a);
        String cheese = option(a, burger, "Extra cheese"), medium = option(a, burger, "Medium");
        String shift = openShift(a, cashier, "20.000");
        String order = UUID.randomUUID().toString();

        op(a, cashier, "OPEN", order, Map.of("open", open("DINE_IN", t1, 3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.status").value("OPEN"))
                .andExpect(jsonPath("$.data.order.waiterName").value("Walt Waiter"))
                .andExpect(jsonPath("$.data.order.guestCount").value(3));
        // 2 burgers with extra cheese and a note, 2 cokes. Stock leaves when the items are sent.
        String burgerLine = UUID.randomUUID().toString();
        op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(
                line(burgerLine, burger, List.of(cheese, medium), 2, "5.500", "No onion", "MAIN"),
                line(UUID.randomUUID().toString(), coke, List.of(), 2, "1.000", null, "DRINK")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.total").value(13.0))
                .andExpect(jsonPath("$.data.order.lines[0].note").value("No onion"))
                .andExpect(jsonPath("$.data.order.lines[0].modifiers[0].name").value("Extra cheese"));
        assertThat(stock(burger)).isEqualTo(8);
        // A burger without the required cooking choice is refused (no invalid item on an open order).
        op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(
                line(UUID.randomUUID().toString(), burger, List.of(cheese), 1, "5.500", null, null))))
                .andExpect(status().isUnprocessableContent());
        // Later: dessert. Only the new line is added.
        String dessertLine = UUID.randomUUID().toString();
        op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(line(dessertLine, dessert, List.of(), 1, "3.000", null, "DESSERT"))))
                .andExpect(jsonPath("$.data.order.lines.length()").value(3)).andExpect(jsonPath("$.data.order.total").value(16.0));

        // T1 → T2 (T2 free), audited.
        op(a, cashier, "MOVE_TABLE", order, Map.of("move", Map.of("fromTableId", t1, "toTableId", t2)))
                .andExpect(jsonPath("$.data.order.tableId").value(t2)).andExpect(jsonPath("$.data.conflicts.length()").value(0));

        // Split by items: guest 1 pays the burgers in cash (11.000); the rest (5.000) on the terminal.
        op(a, cashier, "ADD_PAYMENT", order, Map.of("payment", payment("11.000", "CASH", "ITEMS", shift,
                "[{\"lineUid\":\"" + burgerLine + "\",\"quantity\":2}]"))).andExpect(jsonPath("$.data.order.remaining").value(5.0));
        op(a, cashier, "CLOSE", order, Map.of()).andExpect(jsonPath("$.data.order.status").value("OPEN"))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RESTAURANT_BALANCE_DUE"));
        op(a, cashier, "ADD_PAYMENT", order, Map.of("payment", payment("5.000", "EXTERNAL_TERMINAL", "CUSTOM", shift, null)))
                .andExpect(jsonPath("$.data.order.remaining").value(0.0));
        String closed = op(a, cashier, "CLOSE", order, Map.of()).andExpect(jsonPath("$.data.order.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(closed).contains("\"total\":16.000").contains("\"paid\":16.000");

        // T2 is free again; the order is an ordinary completed POS order, paid.
        String state = send(GET, "/api/pos/restaurant/orders", a.auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(state, "$.data.orders[?(@.status=='OPEN')].orderId")).isEmpty();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, payment_status, source, pos_order_type, restaurant_table_id::text AS t, total, payment_method "
                + "FROM customer_orders WHERE pos_local_order_id = ?::uuid", order);
        assertThat(row).containsEntry("status", "DELIVERED").containsEntry("payment_status", "PAID").containsEntry("source", "POS")
                .containsEntry("pos_order_type", "DINE_IN").containsEntry("t", t2).containsEntry("payment_method", "CARD");
        // Stock moved once per sent line; nothing again at payment or close.
        assertThat(ledgerRows("ORDER_PLACED")).isEqualTo(2);
        assertThat(stock(burger)).isEqualTo(8);
        // The shift's drawer holds only the cash part.
        String sh = send(GET, "/api/dashboard/pos-shifts/" + shift, bearer(ownerA), null).andReturn().getResponse().getContentAsString();
        assertThat(dec(sh, "$.data.shift.totals.cashSales")).isEqualByComparingTo("11.000");
        assertThat(dec(sh, "$.data.shift.totals.terminalSales")).isEqualByComparingTo("5.000");
        assertThat(dec(sh, "$.data.shift.totals.expectedCash")).isEqualByComparingTo("31.000");
        // Audit: opened, items added twice, moved, two payments, closed.
        assertThat(jdbc.queryForList("SELECT event_type FROM pos_restaurant_events e JOIN customer_orders o ON o.id = e.order_id "
                + "WHERE o.pos_local_order_id = ?::uuid ORDER BY e.created_at", String.class, order))
                .containsExactly("OPENED", "ITEMS_ADDED", "ITEMS_ADDED", "TABLE_MOVED", "PAYMENT_ADDED", "PAYMENT_ADDED", "CLOSED");
        // Table history shows it on T2, and on T1 where it started.
        send(GET, "/api/dashboard/restaurant/tables/" + t1 + "/orders", bearer(ownerA), null).andExpect(jsonPath("$.data.length()").value(1));
    }

    // ── idempotency ──────────────────────────────────────────────────────────────────────────

    @Test
    void retriesNeverDuplicateAnOrderALineOrAPayment() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        String version = catalogVersion(a);
        String order = UUID.randomUUID().toString();
        Map<String, Object> takeaway = open("TAKEAWAY", null, null);
        Op openOp = new Op(a, cashier, "OPEN", order, Map.of("open", takeaway));
        openOp.send().andExpect(jsonPath("$.data.replayed").value(false));
        openOp.send().andExpect(jsonPath("$.data.replayed").value(true));
        // The same opening retried under a new operation id (same content): replayed, not a second order.
        Op again = new Op(a, cashier, "OPEN", order, Map.of("open", takeaway));
        again.body.put("occurredAt", openOp.body.get("occurredAt"));
        again.send().andExpect(jsonPath("$.data.replayed").value(true));
        String cokeLine = UUID.randomUUID().toString();
        Op add = new Op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(line(cokeLine, coke, List.of(), 3, "1.000", null, null))));
        add.send().andExpect(status().isOk());
        add.send().andExpect(jsonPath("$.data.replayed").value(true));
        // The same line under a new operation id: added once.
        new Op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(line(cokeLine, coke, List.of(), 3, "1.000", null, null))))
                .send().andExpect(jsonPath("$.data.order.lines.length()").value(1)).andExpect(jsonPath("$.data.order.total").value(3.0));
        assertThat(stock(coke)).isEqualTo(17);
        Map<String, Object> pay = payment("3.000", "CASH", "FULL", null, null);
        Op payOp = new Op(a, cashier, "ADD_PAYMENT", order, Map.of("payment", pay));
        payOp.send().andExpect(status().isOk());
        payOp.send().andExpect(jsonPath("$.data.replayed").value(true));
        new Op(a, cashier, "ADD_PAYMENT", order, Map.of("payment", pay)).send().andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.order.paid").value(3.0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_order_payments p JOIN customer_orders o ON o.id = p.order_id "
                + "WHERE o.pos_local_order_id = ?::uuid", Long.class, order)).isEqualTo(1);
        // The same operation id with different content is refused.
        payOp.body.put("occurredAt", Instant.now().toString());
        payOp.send().andExpect(status().isConflict());
        op(a, cashier, "CLOSE", order, Map.of()).andExpect(jsonPath("$.data.order.status").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders WHERE pos_local_order_id = ?::uuid", Long.class, order)).isEqualTo(1);
    }

    // ── discount + split ─────────────────────────────────────────────────────────────────────

    @Test
    void aDiscountedBillSplitEquallyAddsUpToTheDiscountedTotalAndClosingCountsTheOfferOnce() throws Exception {
        String offer = idOf(send(POST, "/api/dashboard/offers", bearer(ownerA), "{\"storeId\":\"" + storeA
                + "\",\"code\":\"TEN\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"maxUses\":5,\"active\":true}").andExpect(status().isOk()));
        Device a = activate(storeA, ownerA, "Counter");
        String version = catalogVersion(a);
        String shift = openShift(a, cashier, "0.000");
        String order = UUID.randomUUID().toString();
        op(a, cashier, "OPEN", order, Map.of("open", open("DINE_IN", t1, 3)));
        // 2 fries (4.000) + 2 dessert (6.000) = 10.000; 10% off → 9.000; three guests.
        op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(
                line(UUID.randomUUID().toString(), fries, List.of(), 2, "2.000", null, null),
                line(UUID.randomUUID().toString(), dessert, List.of(), 2, "3.000", null, "DESSERT"))));
        op(a, cashier, "APPLY_DISCOUNT", order, Map.of("discount", Map.of("offerId", offer, "code", "TEN", "catalogVersion", version)))
                .andExpect(jsonPath("$.data.order.discount").value(1.0)).andExpect(jsonPath("$.data.order.total").value(9.0));
        // Equal split of the discounted 9.000 in 3 (uneven splits: RestaurantPolicyTest).
        for (String part : List.of("3.000", "3.000")) {
            op(a, cashier, "ADD_PAYMENT", order, Map.of("payment", payment(part, "CASH", "EQUAL", shift, "{\"shares\":3}")));
        }
        op(a, cashier, "ADD_PAYMENT", order, Map.of("payment", payment("3.000", "EXTERNAL_TERMINAL", "EQUAL", shift, "{\"shares\":3}")))
                .andExpect(jsonPath("$.data.order.remaining").value(0.0));
        op(a, cashier, "CLOSE", order, Map.of()).andExpect(jsonPath("$.data.order.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.conflicts.length()").value(0));
        // A retried close (another operation id) closes nothing twice and counts the offer once.
        op(a, cashier, "CLOSE", order, Map.of()).andExpect(jsonPath("$.data.order.status").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT times_used FROM offers WHERE id = ?::uuid", Integer.class, offer)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, payment_status, subtotal, discount, total FROM customer_orders WHERE pos_local_order_id = ?::uuid", order);
        assertThat(row).containsEntry("status", "DELIVERED").containsEntry("payment_status", "PAID");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("9.000");
        String sh = send(GET, "/api/dashboard/pos-shifts/" + shift, bearer(ownerA), null).andReturn().getResponse().getContentAsString();
        assertThat(dec(sh, "$.data.shift.totals.cashSales")).isEqualByComparingTo("6.000");
        assertThat(dec(sh, "$.data.shift.totals.terminalSales")).isEqualByComparingTo("3.000");
    }

    // ── returns (POS-23) ─────────────────────────────────────────────────────────────────────

    @Test
    void anOpenTableCannotBeReturnedButASettledRestaurantOrderReturnsLikeAnySale() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        String version = catalogVersion(a);
        String order = UUID.randomUUID().toString();
        op(a, owner, "OPEN", order, Map.of("open", open("DINE_IN", t1, 2)));
        op(a, owner, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(line(UUID.randomUUID().toString(), coke, List.of(), 2, "1.000", null, null))));
        String serverId = jdbc.queryForObject("SELECT id::text FROM customer_orders WHERE pos_local_order_id = ?::uuid", String.class, order);
        send(POST, "/api/pos/returns/sync", a.auth(), returnBody(a, serverId)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_RETURN_ORDER_OPEN"));
        op(a, owner, "ADD_PAYMENT", order, Map.of("payment", payment("2.000", "CASH", "FULL", null, null)));
        op(a, owner, "CLOSE", order, Map.of()).andExpect(jsonPath("$.data.order.status").value("COMPLETED"));
        send(POST, "/api/pos/returns/sync", a.auth(), returnBody(a, serverId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].acceptedQuantity").value(1));
        assertThat(stock(coke)).isEqualTo(19); // 20 − 2 sent + 1 returned and restocked
    }

    private String returnBody(Device a, String serverOrderId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", UUID.randomUUID().toString());
        body.put("originDeviceId", a.id());
        body.put("localReturnId", UUID.randomUUID().toString());
        body.put("returnNumber", "RET-" + COUNTER.incrementAndGet());
        body.put("kind", "RETURN");
        body.put("originalOrderId", serverOrderId);
        body.put("returnedAt", Instant.now().toString());
        body.put("currency", "JOD");
        body.put("reason", "CUSTOMER_CHANGED_MIND");
        body.put("refundTotal", "1.000");
        body.put("exchangeCredit", "0");
        body.put("refundPaidOut", "1.000");
        body.put("refundMethod", "CASH");
        body.put("items", List.of(Map.of("lineNo", 1, "productId", coke, "quantity", 1, "returnedBefore", 0, "refundAmount", "1.000", "disposition", "RESTOCK")));
        body.put("staff", Map.of("userId", owner.getId().toString(), "name", owner.getFullName()));
        body.put("overrides", List.of());
        return json.writeValueAsString(body);
    }

    // ── takeaway / delivery ──────────────────────────────────────────────────────────────────

    @Test
    void takeawayNeedsNoTableAndDeliveryNeedsContactAndAddressWithTheZonesFee() throws Exception {
        String zone = idOf(send(POST, "/api/dashboard/delivery-zones", bearer(ownerA), "{\"storeId\":\"" + storeA
                + "\",\"name\":\"Downtown\",\"areas\":[\"Center\"],\"minOrder\":0,\"deliveryFee\":1.5,\"active\":true}").andExpect(status().isOk()));
        Device a = activate(storeA, ownerA, "Counter");
        Map<String, Object> takeaway = open("TAKEAWAY", null, null);
        takeaway.put("pickupName", "Sara");
        takeaway.put("ticketNumber", "#104");
        op(a, cashier, "OPEN", UUID.randomUUID().toString(), Map.of("open", takeaway))
                .andExpect(jsonPath("$.data.order.pickupName").value("Sara")).andExpect(jsonPath("$.data.order.ticketNumber").value("#104"));
        Map<String, Object> bad = open("DELIVERY", null, null);
        op(a, cashier, "OPEN", UUID.randomUUID().toString(), Map.of("open", bad)).andExpect(status().isUnprocessableContent());
        Map<String, Object> delivery = open("DELIVERY", null, null);
        delivery.put("customer", Map.of("name", "Rana", "phone", "+962790000001"));
        delivery.put("deliveryAddress", "Street 5, Building 2");
        delivery.put("deliveryZoneId", zone);
        delivery.put("deliveryFee", "1.500");
        op(a, cashier, "OPEN", UUID.randomUUID().toString(), Map.of("open", delivery))
                .andExpect(jsonPath("$.data.order.orderType").value("DELIVERY")).andExpect(jsonPath("$.data.order.deliveryFee").value(1.5))
                .andExpect(jsonPath("$.data.order.total").value(1.5)).andExpect(jsonPath("$.data.order.customerPhone").value("+962790000001"))
                .andExpect(jsonPath("$.data.order.deliveryAddress").value("Street 5, Building 2"));
        // A table on a takeaway is refused.
        op(a, cashier, "OPEN", UUID.randomUUID().toString(), Map.of("open", open("TAKEAWAY", t1, null))).andExpect(status().isUnprocessableContent());
    }

    // ── void, merge, transfer ────────────────────────────────────────────────────────────────

    @Test
    void aVoidIsAuditedRestocksThroughTheLedgerAndNeedsAManager() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        String version = catalogVersion(a);
        String order = UUID.randomUUID().toString(), friesLine = UUID.randomUUID().toString();
        op(a, cashier, "OPEN", order, Map.of("open", open("DINE_IN", t1, 2)));
        op(a, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(line(friesLine, fries, List.of(), 3, "2.000", null, null))));
        assertThat(stock(fries)).isEqualTo(7);
        // The waiter voids 1 alone: kept (it happened), flagged for the manager.
        op(a, cashier, "VOID_LINE", order, Map.of("voidLine", Map.of("lineUid", friesLine, "quantity", 1, "reason", "ENTRY_ERROR", "restock", true, "baseLineVersion", 1)))
                .andExpect(jsonPath("$.data.order.lines[0].quantity").value(2)).andExpect(jsonPath("$.data.order.lines[0].voidedQuantity").value(1))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RESTAURANT_APPROVAL_MISSING"));
        assertThat(stock(fries)).isEqualTo(8);
        assertThat(ledgerRows("POS_VOID")).isEqualTo(1);
        // With the owner's approval, wasted (not restocked): no conflict, no stock back.
        Op v = new Op(a, cashier, "VOID_LINE", order, Map.of("voidLine", Map.of("lineUid", friesLine, "quantity", 1, "reason", "KITCHEN_ERROR", "restock", false, "baseLineVersion", 2)));
        v.approvedBy(owner, "RESTAURANT_VOID_APPROVAL");
        v.send().andExpect(jsonPath("$.data.conflicts.length()").value(0)).andExpect(jsonPath("$.data.order.total").value(2.0));
        assertThat(stock(fries)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_restaurant_events e JOIN customer_orders o ON o.id = e.order_id "
                + "WHERE o.pos_local_order_id = ?::uuid AND e.event_type = 'ITEM_VOIDED'", Long.class, order)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_manager_overrides WHERE action = 'RESTAURANT_VOID_APPROVAL' AND verified AND store_id = ?::uuid",
                Long.class, storeA)).isEqualTo(1);
        // OTHER needs a note.
        op(a, cashier, "VOID_LINE", order, Map.of("voidLine", Map.of("lineUid", friesLine, "quantity", 1, "reason", "OTHER", "restock", true)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void mergingTwoTablesKeepsOneOrderAndMovingItemsKeepsTheirPriceSnapshot() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        String version = catalogVersion(a);
        String o1 = UUID.randomUUID().toString(), o2 = UUID.randomUUID().toString();
        op(a, owner, "OPEN", o1, Map.of("open", open("DINE_IN", t1, 2)));
        op(a, owner, "OPEN", o2, Map.of("open", open("DINE_IN", t2, 2)));
        String cokes = UUID.randomUUID().toString();
        op(a, owner, "ADD_ITEMS", o1, Map.of("catalogVersion", version, "items", List.of(line(cokes, coke, List.of(), 2, "1.000", null, null))));
        op(a, owner, "ADD_ITEMS", o2, Map.of("catalogVersion", version, "items", List.of(line(UUID.randomUUID().toString(), fries, List.of(), 1, "2.000", null, null))));
        op(a, owner, "ADD_PAYMENT", o2, Map.of("payment", payment("1.000", "CASH", "CUSTOM", null, null)));
        // Merge T2's order into T1's: one order with both lines and the payment, nothing duplicated.
        op(a, owner, "MERGE", o1, Map.of("merge", Map.of("sourceOrderId", o2))).andExpect(jsonPath("$.data.order.lines.length()").value(2))
                .andExpect(jsonPath("$.data.order.total").value(4.0)).andExpect(jsonPath("$.data.order.paid").value(1.0))
                .andExpect(jsonPath("$.data.otherOrder.status").value("MERGED")).andExpect(jsonPath("$.data.conflicts.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items i JOIN customer_orders o ON o.id = i.order_id WHERE o.store_id = ?::uuid",
                Long.class, storeA)).isEqualTo(2);
        // A change sent to the merged-away order follows it.
        op(a, owner, "UPDATE_DETAILS", o2, Map.of("details", Map.of("guestCount", 4))).andExpect(jsonPath("$.data.order.orderId").value(o1))
                .andExpect(jsonPath("$.data.order.guestCount").value(4));
        // Move 1 coke to T2 as a new order: the price snapshot moves with it.
        String o3 = UUID.randomUUID().toString(), moved = UUID.randomUUID().toString();
        op(a, owner, "TRANSFER_ITEMS", o1, Map.of("transfer", Map.of("targetOrderId", o3, "openTarget", open("DINE_IN", t2, 1),
                "lines", List.of(Map.of("lineUid", cokes, "quantity", 1, "newLineUid", moved)))))
                .andExpect(jsonPath("$.data.order.total").value(3.0)).andExpect(jsonPath("$.data.otherOrder.total").value(1.0))
                .andExpect(jsonPath("$.data.otherOrder.tableId").value(t2)).andExpect(jsonPath("$.data.otherOrder.lines[0].unitPrice").value(1.0));
        assertThat(stock(coke)).isEqualTo(18); // moving goods never moves stock again
    }

    // ── multi-device ─────────────────────────────────────────────────────────────────────────

    @Test
    void twoTillsAddingToOneTableBothKeepTheirItemsAndChangingTheSameLineIsAConflict() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        Device b = activate(storeA, ownerA, "Waiter tablet");
        String va = catalogVersion(a), vb = catalogVersion(b);
        String order = UUID.randomUUID().toString(), burgerLine = UUID.randomUUID().toString();
        String medium = option(a, burger, "Medium");
        op(a, owner, "OPEN", order, Map.of("open", open("DINE_IN", t1, 2)));
        op(a, owner, "ADD_ITEMS", order, Map.of("catalogVersion", va, "items", List.of(line(burgerLine, burger, List.of(medium), 1, "5.000", null, null))));
        // Both offline: A adds a coke, B adds fries (B learned the order from the state pull).
        send(GET, "/api/pos/restaurant/orders", b.auth(), null).andExpect(jsonPath("$.data.orders[0].orderId").value(order));
        op(a, owner, "ADD_ITEMS", order, Map.of("catalogVersion", va, "items", List.of(line(UUID.randomUUID().toString(), coke, List.of(), 1, "1.000", null, null))));
        op(b, cashier, "ADD_ITEMS", order, Map.of("catalogVersion", vb, "items", List.of(line(UUID.randomUUID().toString(), fries, List.of(), 1, "2.000", null, null))))
                .andExpect(jsonPath("$.data.order.lines.length()").value(3)).andExpect(jsonPath("$.data.order.total").value(8.0));
        // A voids the burger (line v1 → v2) while B edits the same burger's note from v1: explicit conflict, nothing lost.
        op(a, owner, "VOID_LINE", order, Map.of("voidLine", Map.of("lineUid", burgerLine, "quantity", 1, "reason", "CUSTOMER_CHANGED", "restock", true, "baseLineVersion", 1)))
                .andExpect(jsonPath("$.data.conflicts.length()").value(0));
        op(b, cashier, "UPDATE_LINE", order, Map.of("lineUpdate", Map.of("lineUid", burgerLine, "note", "Well done", "baseLineVersion", 1)))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RESTAURANT_LINE_CONFLICT"))
                .andExpect(jsonPath("$.data.order.lines[0].note").doesNotExist());
        // Two tills opened the same table offline: both kept, flagged.
        op(b, cashier, "OPEN", UUID.randomUUID().toString(), Map.of("open", open("DINE_IN", t1, 2)))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RESTAURANT_TABLE_OCCUPIED"));
        // Both take the last payment offline: the money is kept, the overpayment flagged.
        op(a, owner, "ADD_PAYMENT", order, Map.of("payment", payment("3.000", "CASH", "FULL", null, null)));
        op(b, cashier, "ADD_PAYMENT", order, Map.of("payment", payment("3.000", "CASH", "FULL", null, null)))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RESTAURANT_OVERPAID"));
        send(GET, "/api/dashboard/pos-sync/conflicts?storeId=" + storeA, bearer(ownerA), null).andExpect(jsonPath("$.data.open").value(3));
    }

    // ── tenant isolation ─────────────────────────────────────────────────────────────────────

    @Test
    void anotherStoreCanNeitherSeeNorTouchThisStoresTablesOrOrders() throws Exception {
        Device a = activate(storeA, ownerA, "Counter");
        Device other = activate(storeB, ownerB, "Other till");
        String order = UUID.randomUUID().toString();
        op(a, owner, "OPEN", order, Map.of("open", open("DINE_IN", t1, 2))).andExpect(status().isOk());
        // Store B's till cannot open on A's table, add to, pay or close A's order, or see it.
        op(other, owner, "OPEN", UUID.randomUUID().toString(), Map.of("open", open("DINE_IN", t1, 2)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RESTAURANT_TABLE_UNKNOWN"));
        op(other, owner, "ADD_PAYMENT", order, Map.of("payment", payment("1.000", "CASH", "CUSTOM", null, null)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RESTAURANT_ORDER_UNKNOWN"));
        op(other, owner, "CLOSE", order, Map.of()).andExpect(status().isUnprocessableContent());
        op(other, owner, "MOVE_TABLE", order, Map.of("move", Map.of("fromTableId", t1, "toTableId", t2))).andExpect(status().isUnprocessableContent());
        assertThat(JsonPath.<List<Object>>read(send(GET, "/api/pos/restaurant/orders", other.auth(), null).andReturn().getResponse()
                .getContentAsString(), "$.data.orders")).isEmpty();
        send(GET, "/api/dashboard/restaurant/tables/" + t1 + "/orders", bearer(ownerB), null).andExpect(status().is4xxClientError());
        // The dashboard cannot advance an open restaurant order behind the tills' back.
        String serverId = jdbc.queryForObject("SELECT id::text FROM customer_orders WHERE pos_local_order_id = ?::uuid", String.class, order);
        send(PUT, "/api/dashboard/orders/" + serverId + "/status", bearer(ownerA), "{\"status\":\"DELIVERED\"}").andExpect(status().isConflict());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    record Device(String id, String credential) {
        String auth() {
            return "PosDevice " + credential;
        }
    }

    final class Op {
        final Map<String, Object> body = new LinkedHashMap<>();

        Op(Device device, User staff, String type, String orderId, Map<String, Object> extra) {
            body.put("operationId", UUID.randomUUID().toString());
            body.put("originDeviceId", device.id);
            body.put("type", type);
            body.put("orderId", orderId);
            body.put("occurredAt", Instant.now().minusSeconds(30).toString());
            body.put("staff", Map.of("userId", staff.getId().toString(), "name", staff.getFullName()));
            body.put("overrides", new ArrayList<>());
            body.putAll(extra);
            this.device = device;
        }

        final Device device;

        void approvedBy(User manager, String action) {
            body.put("overrides", List.of(Map.of("action", action, "managerId", manager.getId().toString(), "managerName", manager.getFullName(),
                    "approvedAt", Instant.now().minusSeconds(40).toString())));
        }

        ResultActions send() throws Exception {
            return PosRestaurantIT.this.send(POST, "/api/pos/restaurant/operations", device.auth(), json.writeValueAsString(body));
        }
    }

    private ResultActions op(Device device, User staff, String type, String orderId, Map<String, Object> extra) throws Exception {
        return new Op(device, staff, type, orderId, extra).send();
    }

    private Map<String, Object> open(String type, String tableId, Integer guests) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderType", type);
        m.put("tableId", tableId);
        m.put("guestCount", guests);
        m.put("receiptNumber", "POSR-" + (700000 + COUNTER.incrementAndGet()));
        m.put("currency", "JOD");
        return m;
    }

    private static Map<String, Object> line(String uid, String productId, List<String> modifiers, int qty, String unit, String note, String course) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineUid", uid);
        m.put("productId", productId);
        m.put("modifierOptionIds", modifiers);
        m.put("quantity", qty);
        m.put("unitPrice", unit);
        m.put("lineTotal", new BigDecimal(unit).multiply(BigDecimal.valueOf(qty)).toPlainString());
        m.put("note", note);
        m.put("course", course);
        return m;
    }

    private static Map<String, Object> payment(String amount, String method, String mode, String shiftId, String allocation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", UUID.randomUUID().toString());
        m.put("amount", amount);
        m.put("method", method);
        m.put("splitMode", mode);
        m.put("shiftId", shiftId);
        m.put("allocation", allocation);
        return m;
    }

    private String openShift(Device device, User staff, String cash) throws Exception {
        String id = UUID.randomUUID().toString();
        send(POST, "/api/pos/shifts/open", device.auth(), "{\"operationId\":\"" + UUID.randomUUID() + "\",\"originDeviceId\":\"" + device.id
                + "\",\"shiftId\":\"" + id + "\",\"shiftNumber\":\"SHF-R-" + COUNTER.incrementAndGet() + "\",\"openedAt\":\"" + Instant.now().minusSeconds(3600)
                + "\",\"currency\":\"JOD\",\"openingCash\":\"" + cash + "\",\"cashier\":{\"userId\":\"" + staff.getId() + "\",\"name\":\"" + staff.getFullName() + "\"}}")
                .andExpect(status().isOk());
        return id;
    }

    private String option(Device device, String productId, String name) throws Exception {
        String catalog = send(GET, "/api/pos/catalog", device.auth(), null).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(catalog, "$.data.products[?(@.id=='" + productId + "')].modifierGroups[*].options[?(@.name=='" + name + "')].id");
        return ids.get(0);
    }

    private Device activate(String storeId, String ownerToken, String name) throws Exception {
        String created = send(POST, "/api/dashboard/pos-devices", bearer(ownerToken), "{\"storeId\":\"" + storeId + "\",\"name\":\"" + name + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(created, "$.data.activationCode");
        String activated = send(POST, "/api/pos/activate", null, "{\"activationCode\":\"" + code + "\",\"installationId\":\"" + UUID.randomUUID()
                + "\",\"platform\":\"windows\",\"appVersion\":\"0.1.0\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Device(JsonPath.read(activated, "$.data.deviceId"), JsonPath.read(activated, "$.data.deviceCredential"));
    }

    private String catalogVersion(Device device) throws Exception {
        return JsonPath.read(send(GET, "/api/pos/catalog", device.auth(), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.catalogVersion");
    }

    private Integer stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    private long ledgerRows(String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE store_id = ?::uuid AND reason = ?", Long.class, UUID.fromString(storeA), reason);
    }

    private static BigDecimal dec(String body, String path) {
        return new BigDecimal(JsonPath.read(body, path).toString());
    }

    private String product(String name, String slug, String price, Integer stock, int sort) {
        return "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"" + name + "\",\"slug\":\"" + slug + "\",\"price\":" + price
                + (stock == null ? "" : ",\"stock\":" + stock) + ",\"sortOrder\":" + sort + "}";
    }

    private ResultActions send(HttpMethod method, String path, String authorization, String body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path).with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        });
        if (authorization != null) req = req.header("Authorization", authorization);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(req);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private User saveUser(Role role, String name, String storeId) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + "-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(role);
        user.setActive(true);
        if (storeId != null) {
            Store store = storeRepository.findById(UUID.fromString(storeId)).orElseThrow();
            user.setStore(store);
        }
        return userRepository.save(user);
    }
}
