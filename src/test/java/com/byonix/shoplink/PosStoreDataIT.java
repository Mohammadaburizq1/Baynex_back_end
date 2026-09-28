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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * POS-12..16 over real HTTP on PostgreSQL: customers, discount codes, staff PINs and permissions,
 * manager approvals and barcodes — each scoped to the device's store. Not @Transactional (uploads
 * commit for real), and every test builds its own merchants, stores and devices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
class PosStoreDataIT {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String PASSWORD = "Owner-pass-42!";

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private String remoteAddr;
    private User ownerAUser;
    private String ownerA;
    private String ownerB;
    private String storeA;
    private String storeB;
    private String burger;
    private String water;

    @BeforeEach
    void setUp() throws Exception {
        Filter chain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(chain).build();
        int n = COUNTER.incrementAndGet();
        remoteAddr = "10.77." + (n / 250) + "." + (n % 250 + 1);
        ownerAUser = saveUser(Role.MERCHANT_OWNER, "Owner Alice", null, null);
        ownerA = jwtService.createAccessToken(ownerAUser);
        ownerB = jwtService.createAccessToken(saveUser(Role.MERCHANT_OWNER, "Owner Bob", null, null));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        storeA = idOf(send(POST, "/api/dashboard/stores", bearer(ownerA),
                "{\"name\":\"Data Cafe\",\"slug\":\"data-a-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\"}")
                .andExpect(status().isOk()));
        storeB = idOf(send(POST, "/api/dashboard/stores", bearer(ownerB),
                "{\"name\":\"Other\",\"slug\":\"data-b-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\"}")
                .andExpect(status().isOk()));
        burger = idOf(send(POST, "/api/dashboard/products", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Burger\",\"slug\":\"burger\",\"price\":6,\"stock\":20,\"sortOrder\":0,\"barcode\":\"6291041500213\"}")
                .andExpect(status().isOk()));
        water = idOf(send(POST, "/api/dashboard/products", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Water\",\"slug\":\"water\",\"price\":0.75,\"sortOrder\":1}")
                .andExpect(status().isOk()));
    }

    // ── POS-12 customers ─────────────────────────────────────────────────────────────────────

    @Test
    void customersAreThisStoresOnlyAndASaleLinksAnAccountOrKeepsAContact() throws Exception {
        User regular = saveUser(Role.CUSTOMER, "Rana Regular", "+962791111111", null);
        User stranger = saveUser(Role.CUSTOMER, "Sami Stranger", "+962792222222", null);
        webOrder(storeA, regular.getId(), "Rana (typed)", "+962791111111");
        webOrder(storeA, null, "Guest Gia", "+962793333333");
        webOrder(storeB, stranger.getId(), "Sami", "+962792222222"); // Store B's customer

        Device a = activate(storeA, ownerA, "Till");
        String customers = send(GET, "/api/pos/customers", a.auth(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customers", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        assertThat(customers).contains("Rana Regular", "Guest Gia", "acct:" + regular.getId(), "guest:+962793333333")
                .doesNotContain("Sami", stranger.getId().toString(), "password", "passwordHash");
        String version = JsonPath.read(customers, "$.data.version");
        send(GET, "/api/pos/customers?knownVersion=" + version, a.auth(), null).andExpect(jsonPath("$.data.unchanged").value(true));

        Catalog c = catalog(a);
        // An account of this store: linked; the name comes from the account, not from the till.
        String linked = sync(a, sale(a, c).water(1).customer(regular.getId(), "Typed At Till", "+962700000000", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED")).andReturn().getResponse().getContentAsString();
        String linkedOrder = JsonPath.read(linked, "$.data.orderId");
        assertThat(jdbc.queryForMap("SELECT customer_id::text AS cid, customer_name, customer_phone FROM customer_orders WHERE id = ?::uuid", UUID.fromString(linkedOrder)))
                .containsEntry("cid", regular.getId().toString()).containsEntry("customer_name", "Rana Regular")
                .containsEntry("customer_phone", "+962791111111");
        // Store B's customer cannot be attached to Store A's sale: kept as a contact, flagged.
        String foreign = sync(a, sale(a, c).water(1).customer(stranger.getId(), "Sami", "+962792222222", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("CUSTOMER_UNLINKED")).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM customer_orders WHERE id = ?::uuid", UUID.class,
                UUID.fromString(JsonPath.read(foreign, "$.data.orderId")))).isNull();

        // A new contact created offline on two tills with the same phone is one customer afterwards.
        Device b = activate(storeA, ownerA, "Till 2");
        Catalog cb = catalog(b);
        sync(a, sale(a, c).water(1).customer(null, "Nadia New", "+962794444444", "nadia@example.test")).andExpect(status().isOk());
        sync(b, sale(b, cb).water(2).customer(null, "Nadia N.", "+962794444444", null)).andExpect(status().isOk());
        String after = send(GET, "/api/pos/customers", a.auth(), null).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> nadia = JsonPath.read(after, "$.data.customers[?(@.phone=='+962794444444')]");
        assertThat(nadia).hasSize(1);
        assertThat(nadia.get(0).get("orderCount")).isEqualTo(2);
        // The dashboard's Customers page sees POS customers too, walk-ins are still not invented.
        sync(a, sale(a, c).water(1)).andExpect(status().isOk());
        send(GET, "/api/dashboard/customers?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.phone=='+962794444444')].orderCount").value(2))
                .andExpect(jsonPath("$.data[?(@.name=='Walk-in customer')]", hasSize(0)));
        // Store B's device never sees Store A's customers.
        Device other = activate(storeB, ownerB, "B till");
        assertThat(send(GET, "/api/pos/customers", other.auth(), null).andReturn().getResponse().getContentAsString())
                .doesNotContain("Rana", "Nadia", "Gia");
    }

    // ── POS-13 discounts ─────────────────────────────────────────────────────────────────────

    @Test
    void discountsAreRecomputedFromTheDevicesSnapshotAndNeverTakenOnTrust() throws Exception {
        String pct = offer("TEN", "PERCENTAGE", "10", null, null, null);
        String fixed = offer("FIVE", "FIXED_AMOUNT", "5", "20", null, null);
        String later = offer("LATER", "PERCENTAGE", "50", null, null, OffsetDateTime.now().plusDays(1));
        String limited = offer("ONCE", "FIXED_AMOUNT", "1", null, 1, null);
        offer("OLD", "PERCENTAGE", "30", null, null, null);
        send(PUT, "/api/dashboard/offers/" + idByCode("OLD"), bearer(ownerA), offerBody("OLD", "PERCENTAGE", "30", null, null, null, false))
                .andExpect(status().isOk());

        Device a = activate(storeA, ownerA, "Till");
        Catalog c = catalog(a);
        List<String> codes = JsonPath.read(c.json, "$.data.offers[*].code");
        assertThat(codes).containsExactlyInAnyOrder("FIVE", "LATER", "ONCE", "TEN"); // switched-off OLD is not offered

        // 10% of 3 x 6.000 + 0.750 = 18.750 -> 1.875 exactly; total 16.875.
        String ten = sync(a, sale(a, c).burger(3).water(1).offer(pct, "TEN", "1.875")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(16.875)).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForMap("SELECT discount, total, offer_id::text AS oid FROM customer_orders WHERE id = ?::uuid",
                UUID.fromString(JsonPath.read(ten, "$.data.orderId"))))
                .containsEntry("discount", new java.math.BigDecimal("1.875")).containsEntry("oid", pct);
        // Fixed 5 with a minimum of 20.
        sync(a, sale(a, c).burger(4).offer(fixed, "FIVE", "5")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(19.0));
        sync(a, sale(a, c).burger(3).offer(fixed, "FIVE", "5")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_DISCOUNT_NOT_VALID")); // 18 < 20
        // Not yet started at the time of sale.
        sync(a, sale(a, c).burger(1).offer(later, "LATER", "3")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_DISCOUNT_NOT_VALID"));
        // A manipulated amount, and a code this device was never given.
        sync(a, sale(a, c).burger(3).water(1).offer(pct, "TEN", "5")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_DISCOUNT_MISMATCH"));
        sync(a, sale(a, c).burger(1).offer(UUID.randomUUID().toString(), "TEN", "0.600")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_UNKNOWN_DISCOUNT"));

        // Rules change after an offline sale: the sale keeps its total, the change is recorded.
        Sale beforeChange = sale(a, c).burger(1).offer(pct, "TEN", "0.600");
        send(PUT, "/api/dashboard/offers/" + pct, bearer(ownerA), offerBody("TEN", "PERCENTAGE", "20", null, null, null, false))
                .andExpect(status().isOk());
        sync(a, beforeChange).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5.4))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("DISCOUNT_CHANGED"));
        // Usage limit: two offline tills used the single-use code; both sales are kept, one is flagged.
        Device b = activate(storeA, ownerA, "Till 2");
        Catalog cb = catalog(b);
        sync(a, sale(a, c).water(2).offer(limited, "ONCE", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SYNCED"));
        sync(b, sale(b, cb).water(2).offer(limited, "ONCE", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("DISCOUNT_LIMIT_REACHED")).andExpect(jsonPath("$.data.total").value(0.5));
        assertThat(jdbc.queryForObject("SELECT times_used FROM offers WHERE id = ?::uuid", Integer.class, limited)).isEqualTo(1);
        // Store B's device cannot use Store A's code.
        Device other = activate(storeB, ownerB, "B till");
        assertThat(catalog(other).json).doesNotContain("\"TEN\"", "\"FIVE\"");
    }

    // ── POS-14 staff ─────────────────────────────────────────────────────────────────────────

    @Test
    void pinsAreHashedPerPersonAndSalesRecordTheCashierAndApprovals() throws Exception {
        User cashier = saveUser(Role.MERCHANT_STAFF, "Casey Cashier", null, storeA);
        User manager = saveUser(Role.MERCHANT_STAFF, "Mona Manager", null, storeA);
        User outsider = saveUser(Role.MERCHANT_STAFF, "Omar Outsider", null, storeB);
        send(PUT, "/api/dashboard/staff/" + manager.getId() + "/permissions", bearer(ownerA),
                "{\"grants\":[{\"section\":\"POS\",\"level\":\"EDIT\"}]}").andExpect(status().isOk());

        // A PIN is set online by its owner, confirming the account password; never stored as the PIN.
        String cashierToken = jwtService.createAccessToken(cashier);
        send(PUT, "/api/dashboard/pos-pin", bearer(cashierToken), "{\"currentPassword\":\"wrong\",\"pin\":\"4821\"}")
                .andExpect(status().isBadRequest());
        send(PUT, "/api/dashboard/pos-pin", bearer(cashierToken), "{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"1111\"}")
                .andExpect(status().isBadRequest()); // trivial PIN
        send(PUT, "/api/dashboard/pos-pin", bearer(cashierToken), "{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"4821\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.set").value(true));
        send(PUT, "/api/dashboard/pos-pin", bearer(jwtService.createAccessToken(manager)),
                "{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"7305\"}").andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("SELECT pin_salt, pin_hash, iterations FROM pos_staff_pins WHERE user_id = ?", cashier.getId());
        assertThat(row.values().toString()).doesNotContain("4821");

        Device a = activate(storeA, ownerA, "Till");
        String staff = send(GET, "/api/pos/staff", a.auth(), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(staff).contains("Owner Alice", "Casey Cashier", "Mona Manager").doesNotContain("Omar", "passwordHash", "password_hash", "4821");
        List<Map<String, Object>> casey = JsonPath.read(staff, "$.data.staff[?(@.name=='Casey Cashier')]");
        assertThat(casey.get(0)).containsEntry("posLevel", "VIEW").containsEntry("owner", false);
        List<Map<String, Object>> mona = JsonPath.read(staff, "$.data.staff[?(@.name=='Mona Manager')]");
        assertThat(mona.get(0)).containsEntry("posLevel", "EDIT");
        // The hash the device gets verifies the PIN with plain PBKDF2-HMAC-SHA256 (what the app runs offline).
        @SuppressWarnings("unchecked") Map<String, Object> pin = (Map<String, Object>) casey.get(0).get("pin");
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("4821".toCharArray(),
                HexFormat.of().parseHex((String) pin.get("salt")), (Integer) pin.get("iterations"), 256)).getEncoded();
        assertThat(HexFormat.of().formatHex(derived)).isEqualTo(pin.get("hash"));
        assertThat((Integer) pin.get("iterations")).isGreaterThanOrEqualTo(100_000);

        Catalog c = catalog(a);
        String sold = sync(a, sale(a, c).burger(1).staff(cashier.getId(), "Casey Cashier")
                .override("LARGE_DISCOUNT", manager.getId(), "Mona Manager")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SYNCED")).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(sold, "$.data.orderId");
        send(GET, "/api/dashboard/orders/" + orderId, bearer(ownerA), null).andExpect(jsonPath("$.data.posStaffName").value("Casey Cashier"));
        assertThat(jdbc.queryForObject("SELECT pos_staff_id FROM customer_orders WHERE id = ?::uuid", UUID.class, UUID.fromString(orderId)))
                .isEqualTo(cashier.getId());
        assertThat(jdbc.queryForMap("SELECT action, manager_name, acting_staff_name, verified FROM pos_manager_overrides WHERE order_id = ?::uuid",
                UUID.fromString(orderId))).containsEntry("action", "LARGE_DISCOUNT").containsEntry("manager_name", "Mona Manager")
                .containsEntry("acting_staff_name", "Casey Cashier").containsEntry("verified", true);

        // An approval by a cashier (not a POS manager) is kept but flagged; a cashier from Store B is not linked.
        sync(a, sale(a, c).burger(1).staff(cashier.getId(), "Casey Cashier").override("RETRY_REFUSED", cashier.getId(), "Casey Cashier"))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("OVERRIDE_UNVERIFIED"));
        String foreign = sync(a, sale(a, c).burger(1).staff(outsider.getId(), "Omar Outsider")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("STAFF_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT pos_staff_id FROM customer_orders WHERE id = ?::uuid", UUID.class,
                UUID.fromString(JsonPath.read(foreign, "$.data.orderId")))).isNull();

        // Store B's device never receives Store A's staff or PIN hashes; the owner can clear a staff PIN.
        Device other = activate(storeB, ownerB, "B till");
        assertThat(send(GET, "/api/pos/staff", other.auth(), null).andReturn().getResponse().getContentAsString())
                .doesNotContain("Casey", "Mona", (String) pin.get("hash"));
        send(DELETE, "/api/dashboard/pos-pin/" + cashier.getId(), bearer(ownerB), null).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/pos-pin/" + cashier.getId(), bearer(ownerA), null).andExpect(status().isOk());
        List<Map<String, Object>> cleared = JsonPath.read(send(GET, "/api/pos/staff", a.auth(), null).andReturn().getResponse().getContentAsString(),
                "$.data.staff[?(@.name=='Casey Cashier')]");
        assertThat(cleared.get(0).get("pin")).isNull();
    }

    // ── POS-16 barcodes ──────────────────────────────────────────────────────────────────────

    @Test
    void barcodesAreUniquePerStoreAndReachThePos() throws Exception {
        String shake = idOf(send(POST, "/api/dashboard/products", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Shake\",\"slug\":\"shake\",\"price\":3,\"sortOrder\":2}").andExpect(status().isOk()));
        send(PUT, "/api/dashboard/products/" + shake + "/variants", bearer(ownerA),
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"}]}],\"variants\":["
                        + "{\"selection\":[\"S\"],\"price\":3,\"stock\":4,\"available\":true,\"barcode\":\"4006381333931\"},"
                        + "{\"selection\":[\"M\"],\"price\":3.5,\"stock\":2,\"available\":true,\"barcode\":\"6291041500213\"}]}")
                .andExpect(status().isConflict()); // the burger's barcode
        send(PUT, "/api/dashboard/products/" + shake + "/variants", bearer(ownerA),
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"}]}],\"variants\":["
                        + "{\"selection\":[\"S\"],\"price\":3,\"stock\":4,\"available\":true,\"barcode\":\"4006381333931\"},"
                        + "{\"selection\":[\"M\"],\"price\":3.5,\"stock\":2,\"available\":true,\"barcode\":\"4006381333948\"}]}")
                .andExpect(status().isOk());
        send(POST, "/api/dashboard/products", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Dup\",\"slug\":\"dup\",\"price\":1,\"sortOrder\":3,\"barcode\":\"4006381333948\"}")
                .andExpect(status().isConflict());
        send(POST, "/api/dashboard/products", bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Bad\",\"slug\":\"bad\",\"price\":1,\"sortOrder\":3,\"barcode\":\"has space\"}")
                .andExpect(status().isBadRequest());
        // Another store may use the same code.
        send(POST, "/api/dashboard/products", bearer(ownerB),
                "{\"storeId\":\"" + storeB + "\",\"nameEn\":\"B\",\"slug\":\"b\",\"price\":1,\"sortOrder\":0,\"barcode\":\"6291041500213\"}")
                .andExpect(status().isOk());
        // An edit from a client that does not know barcodes keeps it.
        send(PUT, "/api/dashboard/products/" + burger, bearer(ownerA),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Burger\",\"slug\":\"burger\",\"price\":6,\"sortOrder\":0}").andExpect(status().isOk());

        Device a = activate(storeA, ownerA, "Till");
        String json = catalog(a).json;
        assertThat(JsonPath.<List<String>>read(json, "$.data.products[?(@.id=='" + burger + "')].barcode")).containsExactly("6291041500213");
        assertThat(JsonPath.<List<String>>read(json, "$.data.products[?(@.id=='" + shake + "')].variants[*].barcode"))
                .containsExactly("4006381333931", "4006381333948");
        // The storefront does not expose them.
        String slug = jdbc.queryForObject("SELECT slug FROM stores WHERE id = ?::uuid", String.class, storeA);
        assertThat(send(GET, "/api/public/stores/" + slug + "/products", null, null).andReturn().getResponse().getContentAsString())
                .doesNotContain("6291041500213");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    record Device(String id, String credential) {
        String auth() {
            return "PosDevice " + credential;
        }
    }

    record Catalog(String version, String json) {}

    final class Sale {
        final String operationId = UUID.randomUUID().toString();
        final String localOrderId = UUID.randomUUID().toString();
        final String receipt;
        final Catalog catalog;
        final Device device;
        final List<String> lines = new ArrayList<>();
        java.math.BigDecimal subtotal = java.math.BigDecimal.ZERO;
        String discount = "0";
        String extra = "";

        Sale(Device device, Catalog catalog) {
            this.device = device;
            this.catalog = catalog;
            this.receipt = "POSDATA-" + (100000 + COUNTER.incrementAndGet());
        }

        Sale line(String productId, String unit, int qty) {
            java.math.BigDecimal total = new java.math.BigDecimal(unit).multiply(java.math.BigDecimal.valueOf(qty));
            subtotal = subtotal.add(total);
            lines.add("{\"productId\":\"" + productId + "\",\"variantId\":null,\"modifierOptionIds\":[],\"quantity\":" + qty
                    + ",\"unitPrice\":\"" + unit + "\",\"lineTotal\":\"" + total.toPlainString() + "\"}");
            return this;
        }

        Sale burger(int qty) { return line(burger, "6", qty); }

        Sale water(int qty) { return line(water, "0.75", qty); }

        Sale offer(String offerId, String code, String amount) {
            discount = amount;
            extra += ",\"offer\":{\"offerId\":\"" + offerId + "\",\"code\":\"" + code + "\"}";
            return this;
        }

        Sale customer(UUID id, String name, String phone, String email) {
            extra += ",\"customer\":{\"customerId\":" + (id == null ? "null" : "\"" + id + "\"") + ",\"name\":\"" + name + "\",\"phone\":"
                    + (phone == null ? "null" : "\"" + phone + "\"") + ",\"email\":" + (email == null ? "null" : "\"" + email + "\"") + "}";
            return this;
        }

        Sale staff(UUID id, String name) {
            extra += ",\"staff\":{\"userId\":\"" + id + "\",\"name\":\"" + name + "\"}";
            return this;
        }

        Sale override(String action, UUID managerId, String managerName) {
            extra += ",\"overrides\":[{\"action\":\"" + action + "\",\"managerId\":\"" + managerId + "\",\"managerName\":\"" + managerName
                    + "\",\"approvedAt\":\"" + Instant.now().minusSeconds(300) + "\",\"detail\":null}]";
            return this;
        }

        String body() {
            java.math.BigDecimal total = subtotal.subtract(new java.math.BigDecimal(discount));
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + device.id + "\",\"localOrderId\":\"" + localOrderId
                    + "\",\"receiptNumber\":\"" + receipt + "\",\"catalogVersion\":\"" + catalog.version + "\",\"soldAt\":\""
                    + Instant.now().minusSeconds(120) + "\",\"currency\":\"JOD\",\"paymentMethod\":\"CASH\",\"subtotal\":\""
                    + subtotal.toPlainString() + "\",\"discount\":\"" + discount + "\",\"total\":\"" + total.toPlainString()
                    + "\",\"note\":null,\"items\":[" + String.join(",", lines) + "]" + extra + "}";
        }
    }

    private Sale sale(Device device, Catalog catalog) {
        return new Sale(device, catalog);
    }

    private ResultActions sync(Device device, Sale sale) throws Exception {
        return send(POST, "/api/pos/orders/sync", device.auth(), sale.body());
    }

    private String offer(String code, String type, String value, String min, Integer maxUses, OffsetDateTime startsAt) throws Exception {
        return idOf(send(POST, "/api/dashboard/offers", bearer(ownerA), offerBody(code, type, value, min, maxUses, startsAt, true))
                .andExpect(status().isOk()));
    }

    private String offerBody(String code, String type, String value, String min, Integer maxUses, OffsetDateTime startsAt, boolean active) {
        return "{\"storeId\":\"" + storeA + "\",\"code\":\"" + code + "\",\"discountType\":\"" + type + "\",\"discountValue\":" + value
                + (min == null ? "" : ",\"minOrderAmount\":" + min) + (maxUses == null ? "" : ",\"maxUses\":" + maxUses)
                + (startsAt == null ? "" : ",\"startsAt\":\"" + startsAt + "\"") + ",\"active\":" + active + "}";
    }

    private String idByCode(String code) {
        return jdbc.queryForObject("SELECT id::text FROM offers WHERE store_id = ?::uuid AND code = ?", String.class, storeA, code);
    }

    private void webOrder(String storeId, UUID customerId, String name, String phone) {
        jdbc.update("""
                INSERT INTO customer_orders (id, store_id, customer_id, order_code, customer_name, customer_phone, delivery_method,
                    payment_method, payment_status, status, subtotal, delivery_fee, discount, total, currency, created_at, updated_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, 'PICKUP', 'CASH', 'PAID', 'DELIVERED', 5, 0, 0, 5, 'JOD', now(), now())
                """, storeId, customerId, ("W" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12).toUpperCase(), name, phone);
    }

    private Device activate(String storeId, String ownerToken, String name) throws Exception {
        String created = send(POST, "/api/dashboard/pos-devices", bearer(ownerToken),
                "{\"storeId\":\"" + storeId + "\",\"name\":\"" + name + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(created, "$.data.activationCode");
        String activated = send(POST, "/api/pos/activate", null, "{\"activationCode\":\"" + code + "\",\"installationId\":\""
                + UUID.randomUUID() + "\",\"platform\":\"windows\",\"appVersion\":\"0.2.0\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Device(JsonPath.read(activated, "$.data.deviceId"), JsonPath.read(activated, "$.data.deviceCredential"));
    }

    private Catalog catalog(Device device) throws Exception {
        String json = send(GET, "/api/pos/catalog", device.auth(), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Catalog(JsonPath.read(json, "$.data.catalogVersion"), json);
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

    private User saveUser(Role role, String name, String phone, String storeId) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + "-" + UUID.randomUUID() + "@test.com");
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setActive(true);
        if (storeId != null) {
            Store store = storeRepository.findById(UUID.fromString(storeId)).orElseThrow();
            user.setStore(store);
        }
        return userRepository.save(user);
    }
}
