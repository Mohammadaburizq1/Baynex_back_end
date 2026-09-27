package com.byonix.shoplink;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-07..11 offline sale upload, over real HTTP (MockMvc + full security chain) on PostgreSQL.
 *
 * Deliberately NOT @Transactional: idempotency and the concurrent-duplicate race depend on real
 * commits and a real unique key, which a rolled-back test transaction would hide. Every test builds
 * its own merchants, stores and devices, so leftover rows never interfere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
class PosOrderSyncIT {
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private String remoteAddr;
    private String ownerA;
    private String ownerB;
    private String storeA;
    private String storeB;
    private String category;
    private String burger;
    private String shake;
    private String water;
    private String productB;

    @BeforeEach
    void setUp() throws Exception {
        Filter chain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(chain).build();
        int n = IP_COUNTER.incrementAndGet();
        remoteAddr = "10.89." + (n / 250) + "." + (n % 250 + 1);
        ownerA = jwtService.createAccessToken(saveUser(Role.MERCHANT_OWNER, "sync-owner-a"));
        ownerB = jwtService.createAccessToken(saveUser(Role.MERCHANT_OWNER, "sync-owner-b"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        storeA = idOf(send(POST, "/api/dashboard/stores", bearer(ownerA),
                "{\"name\":\"Sync Cafe\",\"slug\":\"sync-a-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\",\"timezone\":\"Asia/Amman\"}")
                .andExpect(status().isOk()));
        category = idOf(send(POST, "/api/dashboard/categories", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Mains\",\"slug\":\"mains\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0}")
                .andExpect(status().isOk()));
        burger = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody("Burger", "burger", "5.250", 5, 0))
                .andExpect(status().isOk()));
        send(PUT, "/api/dashboard/products/" + burger + "/modifier-groups", bearer(ownerA),
                "{\"groups\":[{\"name\":\"Extras\",\"minSelect\":0,\"maxSelect\":2,\"options\":[{\"name\":\"Cheese\",\"priceDelta\":1.125,\"preselected\":false,\"available\":true}]}]}")
                .andExpect(status().isOk());
        shake = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody("Shake", "shake", "3", null, 1))
                .andExpect(status().isOk()));
        send(PUT, "/api/dashboard/products/" + shake + "/variants", bearer(ownerA),
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"}]}],"
                        + "\"variants\":[{\"selection\":[\"S\"],\"sku\":\"SY-S-" + suffix + "\",\"price\":3,\"stock\":4,\"available\":true},"
                        + "{\"selection\":[\"M\"],\"sku\":\"SY-M-" + suffix + "\",\"price\":3.5,\"stock\":2,\"available\":true}]}")
                .andExpect(status().isOk());
        water = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody("Water", "water", "0.750", null, 2))
                .andExpect(status().isOk()));

        storeB = idOf(send(POST, "/api/dashboard/stores", bearer(ownerB),
                "{\"name\":\"Other\",\"slug\":\"sync-b-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\"}")
                .andExpect(status().isOk()));
        productB = idOf(send(POST, "/api/dashboard/products", bearer(ownerB),
                "{\"storeId\":\"" + storeB + "\",\"nameEn\":\"B Item\",\"slug\":\"b-item\",\"price\":1,\"stock\":9,\"sortOrder\":0}")
                .andExpect(status().isOk()));
    }

    // ── golden path + idempotency ────────────────────────────────────────────────────────────

    @Test
    void anOfflineSaleBecomesExactlyOneOrderWithOneStockMovementAndRetriesReturnTheSameOrder() throws Exception {
        Device a = activate(storeA, ownerA, "Counter A");
        Catalog c = catalog(a);
        String cheese = c.modifierOption(burger, "Cheese");
        String small = c.variant(shake, "S");
        Sale sale = new Sale(a, c.version)
                // JOD 5.250 + 1.125 = 6.375 exactly, x2 = 12.750
                .line(burger, null, List.of(cheese), 2, "6.375", "12.750")
                .line(shake, small, List.of(), 1, "3", "3")
                .line(water, null, List.of(), 3, "0.750", "2.250")
                .totals("18.000", "0", "18.000");

        String body = sync(a, sale).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("SYNCED"))
                .andExpect(jsonPath("$.data.operationId").value(sale.operationId))
                .andExpect(jsonPath("$.data.total").value(18.0))
                .andExpect(jsonPath("$.data.conflicts", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(body, "$.data.orderId");
        String orderCode = JsonPath.read(body, "$.data.orderCode");
        assertThat(stockIn(body, burger, null)).isEqualTo(3);
        assertThat(stockIn(body, shake, small)).isEqualTo(3);

        // The dashboard (website) sees it as a normal order, marked POS, paid, with snapshots.
        send(GET, "/api/dashboard/orders/" + orderId, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("POS"))
                .andExpect(jsonPath("$.data.posReceiptNumber").value(sale.receipt))
                .andExpect(jsonPath("$.data.orderCode").value(orderCode))
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.data.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.currency").value("JOD"))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(6.375))
                .andExpect(jsonPath("$.data.items[0].modifiers[0].optionName").value("Cheese"))
                .andExpect(jsonPath("$.data.items[1].variantLabel").value("S"));
        send(GET, "/api/dashboard/orders?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + orderId + "')].source").value("POS"));
        assertThat(ledgerRows(orderCode)).isEqualTo(2); // burger + shake S; water is untracked
        assertThat(dailyOrderCount()).isEqualTo(1);

        // The response was "lost": the device retries the same operation. Same order, nothing moves.
        String replay = sync(a, sale).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.orderCode").value(orderCode))
                .andReturn().getResponse().getContentAsString();
        assertThat(stockIn(replay, burger, null)).isEqualTo(3);
        assertThat(posOrders(storeA)).isEqualTo(1);
        assertThat(ledgerRows(orderCode)).isEqualTo(2);
        assertThat(dailyOrderCount()).isEqualTo(1);

        // The same sale under a new operation id is still that one order.
        String reissued = sale.body().replace(sale.operationId, UUID.randomUUID().toString());
        send(POST, "/api/pos/orders/sync", a.auth(), reissued).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.orderId").value(orderId));
        assertThat(posOrders(storeA)).isEqualTo(1);

        // An operation id reused for a different sale is refused rather than answered.
        Sale other = new Sale(a, c.version).line(water, null, List.of(), 1, "0.750", "0.750").totals("0.750", "0", "0.750");
        send(POST, "/api/pos/orders/sync", a.auth(), other.body().replace(other.operationId, sale.operationId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POS_SYNC_OPERATION_ID_REUSED"));
        assertThat(posOrders(storeA)).isEqualTo(1);
    }

    @Test
    void concurrentUploadsOfTheSameOperationCreateOneOrderAndOneStockMovement() throws Exception {
        Device a = activate(storeA, ownerA, "Counter A");
        Catalog c = catalog(a);
        Sale sale = new Sale(a, c.version).line(burger, null, List.of(), 1, "5.250", "5.250").totals("5.250", "0", "5.250");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<MvcResult> call = () -> {
                start.await();
                return sync(a, sale).andReturn();
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        List<String> orderIds = new ArrayList<>();
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            orderIds.add(JsonPath.read(r.getResponse().getContentAsString(), "$.data.orderId"));
        }
        pool.shutdown();
        assertThat(orderIds).hasSize(4).containsOnly(orderIds.get(0));
        assertThat(posOrders(storeA)).isEqualTo(1);
        assertThat(stock(burger)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE product_id = ?::uuid AND reason = 'ORDER_PLACED'",
                Long.class, burger)).isEqualTo(1);
    }

    // ── price snapshot policy ────────────────────────────────────────────────────────────────

    @Test
    void aCompletedOfflineSaleKeepsTheSnapshotPriceAfterTheWebsiteReprices() throws Exception {
        send(PUT, "/api/dashboard/products/" + water, bearer(ownerA), productBody("Water", "water", "10.000", null, 2))
                .andExpect(status().isOk());
        Device a = activate(storeA, ownerA, "Counter A");
        Catalog synced = catalog(a); // POS synced at 10.000, then went offline and sold at 10.000

        send(PUT, "/api/dashboard/products/" + water, bearer(ownerA), productBody("Water", "water", "12.000", null, 2))
                .andExpect(status().isOk());

        Sale sale = new Sale(a, synced.version).line(water, null, List.of(), 2, "10.000", "20.000").totals("20.000", "0", "20.000");
        String body = sync(a, sale).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED_WITH_CONFLICTS"))
                .andExpect(jsonPath("$.data.total").value(20.0))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("PRICE_CHANGED"))
                .andReturn().getResponse().getContentAsString();
        send(GET, "/api/dashboard/orders/" + JsonPath.read(body, "$.data.orderId"), bearer(ownerA), null)
                .andExpect(jsonPath("$.data.total").value(20.0))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(10.0));
        send(GET, "/api/dashboard/pos-sync/conflicts?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].saleUnitPrice").value(10.0))
                .andExpect(jsonPath("$.data.conflicts[0].currentUnitPrice").value(12.0));

        // A device cannot claim a price its catalog never had…
        Sale inflated = new Sale(a, synced.version).line(water, null, List.of(), 1, "12.000", "12.000").totals("12.000", "0", "12.000");
        sync(a, inflated).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_PRICE_MISMATCH"));
        // …or a catalog version it was never given.
        Sale unknown = new Sale(a, "0123456789abcdef0123456789abcdef").line(water, null, List.of(), 1, "10.000", "10.000")
                .totals("10.000", "0", "10.000");
        sync(a, unknown).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_CATALOG_VERSION_UNKNOWN"));
        // Totals are recomputed, not trusted.
        Sale badTotal = new Sale(a, synced.version).line(water, null, List.of(), 1, "10.000", "10.000").totals("10.000", "0", "9.000");
        sync(a, badTotal).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_TOTAL_MISMATCH"));
        assertThat(posOrders(storeA)).isEqualTo(1);
    }

    // ── multi-device oversell (POS-10/11) ────────────────────────────────────────────────────

    @Test
    void twoOfflineDevicesOversellingKeepBothSalesAndRecordTheShortfall() throws Exception {
        Device a = activate(storeA, ownerA, "Counter A");
        Device b = activate(storeA, ownerA, "Counter B");
        Catalog ca = catalog(a); // both synced at stock 5
        Catalog cb = catalog(b);

        Sale saleA = new Sale(a, ca.version).line(burger, null, List.of(), 3, "5.250", "15.750").totals("15.750", "0", "15.750");
        Sale saleB = new Sale(b, cb.version).line(burger, null, List.of(), 4, "5.250", "21.000").totals("21.000", "0", "21.000");

        String first = sync(a, saleA).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SYNCED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(stockIn(first, burger, null)).isEqualTo(2);
        String second = sync(b, saleB).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED_WITH_CONFLICTS"))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("OVERSOLD"))
                .andExpect(jsonPath("$.data.conflicts[0].requestedQuantity").value(4))
                .andExpect(jsonPath("$.data.conflicts[0].appliedQuantity").value(2))
                .andExpect(jsonPath("$.data.conflicts[0].shortfall").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(stockIn(second, burger, null)).isEqualTo(0);
        assertThat(stock(burger)).isEqualTo(0);
        assertThat(posOrders(storeA)).isEqualTo(2);

        // Ledger: -3 then -2 (what the count could cover), matching the stock exactly.
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT delta, stock_after FROM inventory_adjustments WHERE product_id = ?::uuid AND reason = 'ORDER_PLACED' ORDER BY created_at",
                burger);
        assertThat(ledger).extracting(r -> r.get("delta")).containsExactly(-3, -2);
        assertThat(ledger).extracting(r -> r.get("stock_after")).containsExactly(2, 0);

        // The merchant sees the conflict, with the device and the receipt, and can resolve it.
        String conflicts = send(GET, "/api/dashboard/pos-sync/conflicts?storeId=" + storeA + "&status=OPEN", bearer(ownerA), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open").value(1))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("OVERSOLD"))
                .andExpect(jsonPath("$.data.conflicts[0].deviceName").value("Counter B"))
                .andExpect(jsonPath("$.data.conflicts[0].receiptNumber").value(saleB.receipt))
                .andExpect(jsonPath("$.data.conflicts[0].stockBefore").value(2))
                .andExpect(jsonPath("$.data.conflicts[0].stockAfter").value(0))
                .andReturn().getResponse().getContentAsString();
        String conflictId = JsonPath.read(conflicts, "$.data.conflicts[0].id");
        send(GET, "/api/dashboard/pos-sync/conflicts?storeId=" + storeA, bearer(ownerB), null).andExpect(status().isForbidden());
        send(POST, "/api/dashboard/pos-sync/conflicts/" + conflictId + "/resolve", bearer(ownerB), "{\"note\":\"x\"}")
                .andExpect(status().isForbidden());
        send(POST, "/api/dashboard/pos-sync/conflicts/" + conflictId + "/resolve", bearer(ownerA), "{\"note\":\"Recounted: 2 found in back room\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"));
        send(GET, "/api/dashboard/pos-sync/conflicts?storeId=" + storeA + "&status=OPEN", bearer(ownerA), null)
                .andExpect(jsonPath("$.data.open").value(0));

        // Retrying B's upload never deducts again.
        sync(b, saleB).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("OVERSOLD"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE product_id = ?::uuid AND reason = 'ORDER_PLACED'",
                Long.class, burger)).isEqualTo(2);
    }

    // ── products removed after the sale ──────────────────────────────────────────────────────

    @Test
    void aSaleOfProductsDisabledOrDeletedBeforeUploadIsKeptAndFlagged() throws Exception {
        Device a = activate(storeA, ownerA, "Counter A");
        Catalog c = catalog(a);
        String small = c.variant(shake, "S");
        Sale sale = new Sale(a, c.version)
                .line(burger, null, List.of(), 1, "5.250", "5.250")
                .line(shake, small, List.of(), 1, "3", "3")
                .totals("8.250", "0", "8.250");

        // After the sale but before the upload: burger switched off, shake deleted.
        send(PUT, "/api/dashboard/products/" + burger, bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"categoryId\":\"" + category + "\",\"nameEn\":\"Burger\",\"slug\":\"burger\",\"price\":5.25,\"sortOrder\":0,\"available\":false}")
                .andExpect(status().isOk());
        send(DELETE, "/api/dashboard/products/" + shake, bearer(ownerA), null).andExpect(status().isOk());

        String body = sync(a, sale).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED_WITH_CONFLICTS"))
                .andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(body, "$.data.conflicts[*].type");
        assertThat(types).containsExactlyInAnyOrder("PRODUCT_UNAVAILABLE", "PRODUCT_DELETED");
        assertThat(stock(burger)).isEqualTo(4); // the switched-off product's stock still moved

        send(GET, "/api/dashboard/orders/" + JsonPath.read(body, "$.data.orderId"), bearer(ownerA), null)
                .andExpect(jsonPath("$.data.total").value(8.25))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[1].productNameSnapshot").value("Shake"))
                .andExpect(jsonPath("$.data.items[1].variantLabel").value("S"))
                .andExpect(jsonPath("$.data.items[1].productId").doesNotExist());
    }

    // ── tenancy, revocation, recovery ────────────────────────────────────────────────────────

    @Test
    void aDeviceCannotUploadAnotherStoresSaleAndTheStoreComesFromTheCredential() throws Exception {
        Device a = activate(storeA, ownerA, "Counter A");
        Device b = activate(storeB, ownerB, "Counter B");
        Catalog ca = catalog(a);
        Catalog cb = catalog(b);

        // B uploads with A's product (and A's catalog version): not in anything B was given.
        Sale withA = new Sale(b, ca.version).line(burger, null, List.of(), 1, "5.250", "5.250").totals("5.250", "0", "5.250");
        sync(b, withA).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_CATALOG_VERSION_UNKNOWN"));
        Sale withAInB = new Sale(b, cb.version).line(burger, null, List.of(), 1, "5.250", "5.250").totals("5.250", "0", "5.250");
        sync(b, withAInB).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_UNKNOWN_ITEM"));
        // B claims to upload on behalf of A's device.
        Sale impersonating = new Sale(b, cb.version).origin(a.id).line(productB, null, List.of(), 1, "1", "1").totals("1", "0", "1");
        sync(b, impersonating).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("POS_SYNC_FOREIGN_DEVICE"));
        assertThat(posOrders(storeA)).isZero();
        assertThat(stock(burger)).isEqualTo(5);

        // A's operation id replayed by B: refused without revealing A's order.
        Sale saleA = new Sale(a, ca.version).line(burger, null, List.of(), 1, "5.250", "5.250").totals("5.250", "0", "5.250");
        String applied = sync(a, saleA).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Sale collide = new Sale(b, cb.version).line(productB, null, List.of(), 1, "1", "1").totals("1", "0", "1");
        String response = send(POST, "/api/pos/orders/sync", b.auth(), collide.body().replace(collide.operationId, saleA.operationId))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain((String) JsonPath.read(applied, "$.data.orderCode"));
        assertThat(posOrders(storeB)).isZero();
    }

    @Test
    void aRevokedDeviceCannotUploadButItsReplacementCanRecoverItsUnsyncedSales() throws Exception {
        Device old = activate(storeA, ownerA, "Counter A");
        Catalog c = catalog(old);
        Sale offline = new Sale(old, c.version).line(burger, null, List.of(), 2, "5.250", "10.500").totals("10.500", "0", "10.500");

        send(POST, "/api/dashboard/pos-devices/" + old.id + "/revoke", bearer(ownerA), null).andExpect(status().isOk());
        sync(old, offline).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("POS_DEVICE_INVALID"));
        assertThat(posOrders(storeA)).isZero();

        // The owner activates the same installation as a new device; it uploads the old device's sale.
        Device replacement = activate(storeA, ownerA, "Counter A (replacement)");
        String body = sync(replacement, offline).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED")).andReturn().getResponse().getContentAsString();
        assertThat(stock(burger)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT pos_device_id::text FROM customer_orders WHERE id = ?::uuid", String.class,
                (String) JsonPath.read(body, "$.data.orderId"))).isEqualTo(old.id);
        assertThat(jdbc.queryForObject("SELECT submitted_by_device_id::text FROM pos_sync_operations WHERE operation_id = ?::uuid",
                String.class, offline.operationId)).isEqualTo(replacement.id);
    }

    @Test
    void invalidSalesAreRejectedAndWriteNothing() throws Exception {
        Device a = activate(storeA, ownerA, "Counter A");
        Catalog c = catalog(a);
        // Option required but missing.
        Sale noVariant = new Sale(a, c.version).line(shake, null, List.of(), 1, "3", "3").totals("3", "0", "3");
        sync(a, noVariant).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_INVALID_ITEM"));
        // An add-on id from another product.
        Sale badAddOn = new Sale(a, c.version).line(water, null, List.of(c.modifierOption(burger, "Cheese")), 1, "1.875", "1.875")
                .totals("1.875", "0", "1.875");
        sync(a, badAddOn).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_UNKNOWN_ITEM"));
        // Malformed quantity.
        Sale zero = new Sale(a, c.version).line(water, null, List.of(), 0, "0.750", "0").totals("0", "0", "0");
        sync(a, zero).andExpect(status().isBadRequest());
        // Discounts arrive with POS-13.
        Sale discounted = new Sale(a, c.version).line(water, null, List.of(), 1, "0.750", "0.750").totals("0.750", "0.100", "0.650");
        sync(a, discounted).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_DISCOUNT_NOT_SUPPORTED"));
        assertThat(posOrders(storeA)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_sync_operations WHERE store_id = ?::uuid", Long.class, storeA)).isZero();
        // POS sales never appear as a made-up "customer".
        send(GET, "/api/dashboard/customers?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
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

        String modifierOption(String productId, String name) {
            List<String> ids = JsonPath.read(json, "$.data.products[?(@.id=='" + productId + "')].modifierGroups[*].options[?(@.name=='" + name + "')].id");
            return ids.get(0);
        }
    }

    final class Sale {
        final String operationId = UUID.randomUUID().toString();
        final String localOrderId = UUID.randomUUID().toString();
        final String receipt;
        final String catalogVersion;
        final Instant soldAt = Instant.now().minusSeconds(600);
        String origin;
        final List<String> lines = new ArrayList<>();
        String subtotal;
        String discount;
        String total;

        Sale(Device device, String catalogVersion) {
            this.origin = device.id;
            this.catalogVersion = catalogVersion;
            this.receipt = "POS" + device.id.substring(0, 4).toUpperCase() + "-20260927-" + (100000 + IP_COUNTER.incrementAndGet());
        }

        Sale origin(String deviceId) {
            this.origin = deviceId;
            return this;
        }

        Sale line(String productId, String variantId, List<String> addOns, int quantity, String unitPrice, String lineTotal) {
            lines.add("{\"productId\":\"" + productId + "\",\"variantId\":" + (variantId == null ? "null" : "\"" + variantId + "\"")
                    + ",\"modifierOptionIds\":[" + String.join(",", addOns.stream().map(id -> "\"" + id + "\"").toList()) + "]"
                    + ",\"quantity\":" + quantity + ",\"unitPrice\":\"" + unitPrice + "\",\"lineTotal\":\"" + lineTotal + "\"}");
            return this;
        }

        Sale totals(String subtotal, String discount, String total) {
            this.subtotal = subtotal;
            this.discount = discount;
            this.total = total;
            return this;
        }

        String body() {
            // Money travels as decimal strings, exactly as the POS sends it.
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"localOrderId\":\"" + localOrderId
                    + "\",\"receiptNumber\":\"" + receipt + "\",\"catalogVersion\":\"" + catalogVersion + "\",\"soldAt\":\""
                    + soldAt + "\",\"currency\":\"JOD\",\"paymentMethod\":\"CASH\",\"subtotal\":\"" + subtotal
                    + "\",\"discount\":\"" + discount + "\",\"total\":\"" + total + "\",\"note\":null,\"items\":[" + String.join(",", lines) + "]}";
        }
    }

    private ResultActions sync(Device device, Sale sale) throws Exception {
        return send(POST, "/api/pos/orders/sync", device.auth(), sale.body());
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

    private static Integer stockIn(String syncResponse, String productId, String variantId) {
        List<Map<String, Object>> levels = JsonPath.read(syncResponse, "$.data.inventory");
        return levels.stream()
                .filter(l -> productId.equals(l.get("productId")) && java.util.Objects.equals(variantId, l.get("variantId")))
                .map(l -> (Integer) l.get("stock"))
                .findFirst().orElseThrow(() -> new AssertionError("no stock level for " + productId + "/" + variantId + " in " + syncResponse));
    }

    private Integer stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    private long posOrders(String storeId) {
        return jdbc.queryForObject("SELECT count(*) FROM customer_orders WHERE store_id = ?::uuid AND source = 'POS'", Long.class, storeId);
    }

    private long ledgerRows(String orderCode) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE reference = ?", Long.class, orderCode);
    }

    private long dailyOrderCount() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(order_count), 0) FROM daily_store_sales WHERE store_id = ?::uuid",
                Long.class, UUID.fromString(storeA));
    }

    private String productBody(String name, String slug, String price, Integer stock, int sort) {
        return "{\"storeId\":\"" + storeA + "\",\"categoryId\":\"" + category + "\",\"nameEn\":\"" + name + "\",\"slug\":\"" + slug
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

    private User saveUser(Role role, String label) {
        User user = new User();
        user.setFullName(label);
        user.setEmail(label + "-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(role);
        user.setActive(true);
        return userRepository.save(user);
    }
}
