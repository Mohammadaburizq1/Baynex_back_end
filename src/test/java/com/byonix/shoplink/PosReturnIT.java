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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-23 returns and exchanges over real HTTP (MockMvc + full security chain) on PostgreSQL.
 * Not @Transactional: idempotency depends on real commits and real unique keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
class PosReturnIT {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private String remoteAddr;
    private User owner;
    private String ownerA;
    private String ownerB;
    private String storeA;
    private String storeB;
    private String burger;
    private String shake;
    private String water;

    @BeforeEach
    void setUp() throws Exception {
        Filter chain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(chain).build();
        int n = COUNTER.incrementAndGet();
        remoteAddr = "10.91." + (n / 250) + "." + (n % 250 + 1);
        owner = saveUser(Role.MERCHANT_OWNER, "Olivia Owner", null);
        ownerA = jwtService.createAccessToken(owner);
        ownerB = jwtService.createAccessToken(saveUser(Role.MERCHANT_OWNER, "Other Owner", null));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        storeA = idOf(send(POST, "/api/dashboard/stores", bearer(ownerA),
                "{\"name\":\"Return Cafe\",\"slug\":\"ret-a-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\",\"timezone\":\"Asia/Amman\"}")
                .andExpect(status().isOk()));
        burger = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody("Burger", "burger", "5.250", 5, 0)).andExpect(status().isOk()));
        water = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody("Water", "water", "0.750", null, 1)).andExpect(status().isOk()));
        shake = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody("Shake", "shake", "3", null, 2)).andExpect(status().isOk()));
        send(HttpMethod.PUT, "/api/dashboard/products/" + shake + "/variants", bearer(ownerA),
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"}]}],"
                        + "\"variants\":[{\"selection\":[\"S\"],\"sku\":\"RS-" + suffix + "\",\"price\":3,\"stock\":4,\"available\":true},"
                        + "{\"selection\":[\"M\"],\"sku\":\"RM-" + suffix + "\",\"price\":3.5,\"stock\":4,\"available\":true}]}")
                .andExpect(status().isOk());
        storeB = idOf(send(POST, "/api/dashboard/stores", bearer(ownerB),
                "{\"name\":\"Other\",\"slug\":\"ret-b-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\"}")
                .andExpect(status().isOk()));
    }

    // ── full / partial ───────────────────────────────────────────────────────────────────────

    @Test
    void aFullCashReturnRefundsExactlyRestocksOnceAndIsListedWithTheCustomer() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        Sale sale = new Sale(a, c.version).line(burger, null, 2, "5.250", "10.500").line(water, null, 1, "0.750", "0.750")
                .totals("11.250", "0", "11.250").customer("Rana Regular", "+962791111111");
        String orderId = syncSale(a, sale);
        assertThat(stock(burger)).isEqualTo(3);

        Return ret = new Return(a, orderId).item(1, burger, null, 2, 0, "10.500", "RESTOCK").item(2, water, null, 1, 0, "0.750", "RESTOCK")
                .refund("11.250", "0", "11.250", "CASH").approvedBy(owner);
        String body = send(POST, "/api/pos/returns/sync", a.auth(), ret.body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED"))
                .andExpect(jsonPath("$.data.refundTotal").value(11.25))
                .andReturn().getResponse().getContentAsString();
        assertThat(stock(burger)).isEqualTo(5);
        assertThat(ledgerRows("POS_RETURN")).isEqualTo(1); // water is untracked: nothing to restock
        assertThat(JsonPath.<Integer>read(body, "$.data.items[0].restockedQuantity")).isEqualTo(2);

        String list = send(GET, "/api/dashboard/pos-returns?storeId=" + storeA + "&orderId=" + orderId, bearer(ownerA), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(list, "$.data[0].customerName")).isEqualTo("Rana Regular");
        assertThat(JsonPath.<String>read(list, "$.data[0].managerName")).isEqualTo("Olivia Owner");
        assertThat(JsonPath.<String>read(list, "$.data[0].refundMethod")).isEqualTo("CASH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_manager_overrides WHERE return_id IS NOT NULL AND store_id = ?::uuid",
                Long.class, storeA)).isEqualTo(1);
    }

    @Test
    void partialReturnsAddUpToTheLineAndCanNeverExceedWhatWasSold() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        String orderId = syncSale(a, new Sale(a, c.version).line(burger, null, 3, "5.250", "15.750").line(water, null, 1, "0.750", "0.750")
                .totals("16.500", "0", "16.500"));
        for (int before = 0; before < 3; before++) {
            send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, before, "5.250", "RESTOCK")
                    .refund("5.250", "0", "5.250", "CASH").body()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SYNCED"));
        }
        assertThat(stock(burger)).isEqualTo(5);
        // Already fully returned on this till: the till itself cannot ask for a fourth.
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, 3, "5.250", "RESTOCK")
                .refund("5.250", "0", "5.250", "CASH").body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RETURN_INVALID"));
        assertThat(stock(burger)).isEqualTo(5);
    }

    @Test
    void aDiscountedSaleRefundsTheAllocatedPaidValueNotTheListPrice() throws Exception {
        String ten = idOf(send(POST, "/api/dashboard/offers", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"code\":\"TEN\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"active\":true}")
                .andExpect(status().isOk()));
        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        // 3 × 5.250 + 0.750 = 16.500; 10% = 1.650. Burger share 1.650 × 15.750 / 16.500 = 1.575 → paid 14.175.
        String orderId = syncSale(a, new Sale(a, c.version).line(burger, null, 3, "5.250", "15.750").line(water, null, 1, "0.750", "0.750")
                .totals("16.500", "1.650", "14.850").offer(ten, "TEN"));
        // List price is refused …
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, 0, "5.250", "RESTOCK")
                .refund("5.250", "0", "5.250", "CASH").body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RETURN_AMOUNT_MISMATCH"));
        // … the allocated value (14.175 / 3 = 4.725) is accepted, and the rest adds up to exactly 14.175.
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, 0, "4.725", "RESTOCK")
                .refund("4.725", "0", "4.725", "CASH").body()).andExpect(status().isOk());
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 2, 1, "9.450", "RESTOCK")
                .refund("9.450", "0", "9.450", "CASH").approvedBy(owner).body()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT sum(refund_total) FROM pos_returns WHERE original_order_id = ?::uuid", BigDecimal.class, orderId))
                .isEqualByComparingTo("14.175");
    }

    // ── exchange ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aVariantToVariantExchangeLinksTheReturnToTheReplacementSale() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        String small = c.variant(shake, "S"), medium = c.variant(shake, "M");
        String orderId = syncSale(a, new Sale(a, c.version).line(shake, small, 1, "3", "3").totals("3", "0", "3"));
        int smallBefore = variantStock(small), mediumBefore = variantStock(medium);

        // Return S (3.000), take M (3.500): the customer pays 0.500; nothing is paid out.
        String localReturnId = UUID.randomUUID().toString();
        Sale replacement = new Sale(a, c.version).line(shake, medium, 1, "3.5", "3.5").totals("3.5", "0", "3.5").exchange(localReturnId, "3");
        syncSale(a, replacement);
        Return ret = new Return(a, orderId).localId(localReturnId).exchange(replacement.localOrderId)
                .item(1, shake, small, 1, 0, "3", "RESTOCK").refund("3", "3", "0", null);
        send(POST, "/api/pos/returns/sync", a.auth(), ret.body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED"));
        assertThat(variantStock(small)).isEqualTo(smallBefore + 1);
        assertThat(variantStock(medium)).isEqualTo(mediumBefore - 1);
        assertThat(ledgerRows("POS_EXCHANGE_RETURN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pos_exchange_credit FROM customer_orders WHERE pos_local_order_id = ?::uuid",
                BigDecimal.class, replacement.localOrderId)).isEqualByComparingTo("3");

        // A return claiming a different credit than its replacement sale is kept and flagged.
        String orderId2 = syncSale(a, new Sale(a, c.version).line(shake, small, 1, "3", "3").totals("3", "0", "3"));
        String rid2 = UUID.randomUUID().toString();
        Sale replacement2 = new Sale(a, c.version).line(shake, medium, 1, "3.5", "3.5").totals("3.5", "0", "3.5").exchange(rid2, "3");
        syncSale(a, replacement2);
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId2).localId(rid2).exchange(replacement2.localOrderId)
                .item(1, shake, small, 1, 0, "3", "RESTOCK").refund("3", "2", "1", "CASH").body())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.conflicts[0].type").value("EXCHANGE_MISMATCH"));
    }

    // ── inventory disposition + idempotency ─────────────────────────────────────────────────

    @Test
    void damagedGoodsAreNotRestockedAndARetryNeverRefundsOrRestocksTwice() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        String orderId = syncSale(a, new Sale(a, c.version).line(burger, null, 3, "5.250", "15.750").totals("15.750", "0", "15.750"));
        assertThat(stock(burger)).isEqualTo(2);

        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, 0, "5.250", "DAMAGED")
                .refund("5.250", "0", "5.250", "CASH").reason("DAMAGED", null).body()).andExpect(status().isOk());
        assertThat(stock(burger)).isEqualTo(2);
        assertThat(ledgerRows("POS_RETURN")).isZero();

        Return restock = new Return(a, orderId).item(1, burger, null, 1, 1, "5.250", "RESTOCK").refund("5.250", "0", "5.250", "EXTERNAL_TERMINAL");
        send(POST, "/api/pos/returns/sync", a.auth(), restock.body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        // The response was lost: the till sends the same operation again.
        send(POST, "/api/pos/returns/sync", a.auth(), restock.body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        // … or the same return under a new operation id.
        send(POST, "/api/pos/returns/sync", a.auth(), restock.newOperation().body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(stock(burger)).isEqualTo(3);
        assertThat(ledgerRows("POS_RETURN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_returns WHERE original_order_id = ?::uuid", Long.class, orderId)).isEqualTo(2);
        // An operation id reused for a different return is refused.
        send(POST, "/api/pos/returns/sync", a.auth(), restock.withItemRefund("5.000").body()).andExpect(status().isConflict());
    }

    // ── two tills, one unit ──────────────────────────────────────────────────────────────────

    @Test
    void twoTillsReturningTheSameUnitRefundAndRestockItOnceAndFlagTheSecond() throws Exception {
        Device a = activate(storeA, ownerA, "Till A");
        Device b = activate(storeA, ownerA, "Till B");
        Catalog c = catalog(a);
        String orderId = syncSale(a, new Sale(a, c.version).line(burger, null, 1, "5.250", "5.250").line(water, null, 4, "0.750", "3.000")
                .totals("8.250", "0", "8.250"));
        assertThat(stock(burger)).isEqualTo(4);
        // Both returned the burger offline; A reaches the server first.
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, 0, "5.250", "RESTOCK")
                .refund("5.250", "0", "5.250", "CASH").approvedBy(owner).body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED"));
        String second = send(POST, "/api/pos/returns/sync", b.auth(), new Return(b, orderId).item(1, burger, null, 1, 0, "5.250", "RESTOCK")
                .refund("5.250", "0", "5.250", "CASH").approvedBy(owner).body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED_WITH_CONFLICTS"))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RETURN_QUANTITY_EXCEEDED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(second, "$.data.items[0].acceptedQuantity")).isZero();
        assertThat(new BigDecimal(JsonPath.read(second, "$.data.refundTotal").toString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(JsonPath.read(second, "$.data.requestedRefundTotal").toString())).isEqualByComparingTo("5.250");
        assertThat(stock(burger)).isEqualTo(5); // restocked once
        assertThat(ledgerRows("POS_RETURN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(refund_total) FROM pos_returns WHERE original_order_id = ?::uuid", BigDecimal.class, orderId))
                .isEqualByComparingTo("5.250");
        // The manager sees it with the other POS conflicts.
        send(GET, "/api/dashboard/pos-sync/conflicts?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RETURN_QUANTITY_EXCEEDED"));
    }

    // ── security / approvals ─────────────────────────────────────────────────────────────────

    @Test
    void anotherStoresTillCannotReturnThisStoresSaleAndTamperedAmountsAreRefused() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Device other = activate(storeB, ownerB, "Other till");
        Catalog c = catalog(a);
        String orderId = syncSale(a, new Sale(a, c.version).line(burger, null, 1, "5.250", "5.250").totals("5.250", "0", "5.250"));
        send(POST, "/api/pos/returns/sync", other.auth(), new Return(other, orderId).item(1, burger, null, 1, 0, "5.250", "RESTOCK")
                .refund("5.250", "0", "5.250", "CASH").body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RETURN_ORDER_UNKNOWN"));
        // Store B's owner cannot list store A's returns either.
        send(GET, "/api/dashboard/pos-returns?storeId=" + storeA, bearer(ownerB), null).andExpect(status().is4xxClientError());
        // More money than the line was worth.
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(1, burger, null, 1, 0, "6.000", "RESTOCK")
                .refund("6.000", "0", "6.000", "CASH").body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RETURN_AMOUNT_MISMATCH"));
        // A line that is not on the sale.
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).item(2, water, null, 1, 0, "0.750", "RESTOCK")
                .refund("0.750", "0", "0.750", "CASH").body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_RETURN_ITEM_UNKNOWN"));
        assertThat(stock(burger)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_returns WHERE store_id IN (?::uuid, ?::uuid)", Long.class, storeA, storeB)).isZero();
    }

    @Test
    void aFullReturnWithoutAManagersApprovalIsKeptButFlagged() throws Exception {
        User cashier = saveUser(Role.MERCHANT_STAFF, "Casey Cashier", storeA);
        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        String orderId = syncSale(a, new Sale(a, c.version).line(burger, null, 1, "5.250", "5.250").totals("5.250", "0", "5.250"));
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, orderId).staff(cashier).item(1, burger, null, 1, 0, "5.250", "RESTOCK")
                .refund("5.250", "0", "5.250", "CASH").approvedBy(cashier).body())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RETURN_APPROVAL_MISSING"));
        assertThat(stock(burger)).isEqualTo(5); // kept: the money already changed hands at the till
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    record Device(String id, String credential) {
        String auth() {
            return "PosDevice " + credential;
        }
    }

    record Catalog(String version, String json) {
        String variant(String productId, String label) {
            List<String> ids = JsonPath.read(json, "$.data.products[?(@.id=='" + productId + "')].variants[?(@.label=='" + label + "')].id");
            return ids.get(0);
        }
    }

    final class Sale {
        final String operationId = UUID.randomUUID().toString();
        final String localOrderId = UUID.randomUUID().toString();
        final String receipt;
        final String catalogVersion;
        final String origin;
        final List<String> lines = new ArrayList<>();
        String subtotal, discount, total, extra = "";

        Sale(Device device, String catalogVersion) {
            this.origin = device.id;
            this.catalogVersion = catalogVersion;
            this.receipt = "POS" + device.id.substring(0, 4).toUpperCase() + "-20260928-" + (200000 + COUNTER.incrementAndGet());
        }

        Sale line(String productId, String variantId, int quantity, String unitPrice, String lineTotal) {
            lines.add("{\"productId\":\"" + productId + "\",\"variantId\":" + (variantId == null ? "null" : "\"" + variantId + "\"")
                    + ",\"modifierOptionIds\":[],\"quantity\":" + quantity + ",\"unitPrice\":\"" + unitPrice + "\",\"lineTotal\":\"" + lineTotal + "\"}");
            return this;
        }

        Sale totals(String subtotal, String discount, String total) {
            this.subtotal = subtotal;
            this.discount = discount;
            this.total = total;
            return this;
        }

        Sale customer(String name, String phone) {
            extra += ",\"customer\":{\"customerId\":null,\"name\":\"" + name + "\",\"phone\":\"" + phone + "\",\"email\":null}";
            return this;
        }

        Sale offer(String offerId, String code) {
            extra += ",\"offer\":{\"offerId\":\"" + offerId + "\",\"code\":\"" + code + "\"}";
            return this;
        }

        Sale exchange(String localReturnId, String credit) {
            extra += ",\"exchange\":{\"localReturnId\":\"" + localReturnId + "\",\"credit\":\"" + credit + "\"}";
            return this;
        }

        String body() {
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"localOrderId\":\"" + localOrderId
                    + "\",\"receiptNumber\":\"" + receipt + "\",\"catalogVersion\":\"" + catalogVersion + "\",\"soldAt\":\""
                    + Instant.now().minusSeconds(600) + "\",\"currency\":\"JOD\",\"paymentMethod\":\"CASH\",\"subtotal\":\"" + subtotal
                    + "\",\"discount\":\"" + discount + "\",\"total\":\"" + total + "\",\"note\":null" + extra
                    + ",\"items\":[" + String.join(",", lines) + "]}";
        }
    }

    final class Return {
        String operationId = UUID.randomUUID().toString();
        String localReturnId = UUID.randomUUID().toString();
        final String number = "RET-" + (300000 + COUNTER.incrementAndGet());
        final Instant returnedAt = Instant.now().minusSeconds(60);
        final String origin;
        final String orderId;
        final List<String[]> items = new ArrayList<>();
        String kind = "RETURN", exchangeLocalOrderId = null, reason = "CUSTOMER_CHANGED_MIND", note = null;
        String refundTotal, credit, paidOut, method, staff = null, overrides = "[]";

        Return(Device device, String orderId) {
            this.origin = device.id;
            this.orderId = orderId;
        }

        Return localId(String id) {
            this.localReturnId = id;
            return this;
        }

        Return exchange(String replacementLocalOrderId) {
            this.kind = "EXCHANGE";
            this.exchangeLocalOrderId = replacementLocalOrderId;
            return this;
        }

        Return item(int lineNo, String productId, String variantId, int quantity, int returnedBefore, String refund, String disposition) {
            items.add(new String[]{String.valueOf(lineNo), productId, variantId, String.valueOf(quantity), String.valueOf(returnedBefore), refund, disposition});
            return this;
        }

        Return refund(String total, String credit, String paidOut, String method) {
            this.refundTotal = total;
            this.credit = credit;
            this.paidOut = paidOut;
            this.method = method;
            return this;
        }

        Return reason(String reason, String note) {
            this.reason = reason;
            this.note = note;
            return this;
        }

        Return staff(User user) {
            this.staff = "{\"userId\":\"" + user.getId() + "\",\"name\":\"" + user.getFullName() + "\"}";
            return this;
        }

        Return approvedBy(User manager) {
            this.overrides = "[{\"action\":\"RETURN_APPROVAL\",\"managerId\":\"" + manager.getId() + "\",\"managerName\":\""
                    + manager.getFullName() + "\",\"approvedAt\":\"" + Instant.now().minusSeconds(90) + "\",\"detail\":null}]";
            return this;
        }

        Return newOperation() {
            this.operationId = UUID.randomUUID().toString();
            return this;
        }

        /** Same operation id, different content. */
        Return withItemRefund(String refund) {
            items.get(0)[5] = refund;
            this.refundTotal = refund;
            this.paidOut = refund;
            return this;
        }

        String body() {
            List<String> lines = new ArrayList<>();
            for (String[] i : items) {
                lines.add("{\"lineNo\":" + i[0] + ",\"productId\":\"" + i[1] + "\",\"variantId\":" + (i[2] == null ? "null" : "\"" + i[2] + "\"")
                        + ",\"quantity\":" + i[3] + ",\"returnedBefore\":" + i[4] + ",\"refundAmount\":\"" + i[5] + "\",\"disposition\":\"" + i[6] + "\"}");
            }
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"localReturnId\":\"" + localReturnId
                    + "\",\"returnNumber\":\"" + number + "\",\"kind\":\"" + kind + "\",\"originalOrderId\":\"" + orderId
                    + "\",\"returnedAt\":\"" + returnedAt + "\",\"currency\":\"JOD\",\"reason\":\"" + reason + "\",\"reasonNote\":"
                    + (note == null ? "null" : "\"" + note + "\"") + ",\"refundTotal\":\"" + refundTotal + "\",\"exchangeCredit\":\"" + credit
                    + "\",\"refundPaidOut\":\"" + paidOut + "\",\"refundMethod\":" + (method == null ? "null" : "\"" + method + "\"")
                    + ",\"exchangeLocalOrderId\":" + (exchangeLocalOrderId == null ? "null" : "\"" + exchangeLocalOrderId + "\"")
                    + ",\"staff\":" + (staff == null ? "{\"userId\":\"" + owner.getId() + "\",\"name\":\"Olivia Owner\"}" : staff)
                    + ",\"overrides\":" + overrides + ",\"items\":[" + String.join(",", lines) + "]}";
        }
    }

    private String syncSale(Device device, Sale sale) throws Exception {
        String body = send(POST, "/api/pos/orders/sync", device.auth(), sale.body()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.orderId");
    }

    private Device activate(String storeId, String ownerToken, String name) throws Exception {
        String created = send(POST, "/api/dashboard/pos-devices", bearer(ownerToken),
                "{\"storeId\":\"" + storeId + "\",\"name\":\"" + name + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(created, "$.data.activationCode");
        String activated = send(POST, "/api/pos/activate", null, "{\"activationCode\":\"" + code + "\",\"installationId\":\""
                + UUID.randomUUID() + "\",\"platform\":\"windows\",\"appVersion\":\"0.1.0\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Device(JsonPath.read(activated, "$.data.deviceId"), JsonPath.read(activated, "$.data.deviceCredential"));
    }

    private Catalog catalog(Device device) throws Exception {
        String json = send(GET, "/api/pos/catalog", device.auth(), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Catalog(JsonPath.read(json, "$.data.catalogVersion"), json);
    }

    private Integer stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    private Integer variantStock(String variantId) {
        return jdbc.queryForObject("SELECT stock FROM product_variants WHERE id = ?::uuid", Integer.class, variantId);
    }

    private long ledgerRows(String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE store_id = ?::uuid AND reason = ?",
                Long.class, UUID.fromString(storeA), reason);
    }

    private String productBody(String name, String slug, String price, Integer stock, int sort) {
        return "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"" + name + "\",\"slug\":\"" + slug
                + "\",\"price\":" + price + (stock == null ? "" : ",\"stock\":" + stock) + ",\"sortOrder\":" + sort + "}";
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
