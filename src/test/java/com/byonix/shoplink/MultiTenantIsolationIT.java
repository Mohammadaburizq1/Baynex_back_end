package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.OrderItem;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.PaymentMethod;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.ProductRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant isolation, proven over real HTTP + a real database (H2 via application-test.yml)
 * through the real security filter chain, controllers, services and JPA.
 *
 * Two independent merchants (A and B) each own an ACTIVE store with a full set of tenant-scoped
 * data (category, product, delivery zone, offer, appointment slot, order, storefront theme
 * content). Every test then plays the attacker: merchant B, a staff member of A, a customer, or
 * an anonymous caller tries to read, change, delete or inject data across the tenant boundary.
 * The expectations are deliberately blunt — 403 for a foreign record you can name by id, an empty
 * list for a foreign storeId on a list endpoint, 404 for a foreign record reached through a
 * public store slug — and most denials are paired with a positive control (the same call against
 * the caller's own tenant succeeds) so a passing test can't just mean "the request was malformed".
 *
 * Why this exists: isolation in this codebase is enforced per service method (accessibleStore /
 * ownedStore / ensureSectionAccess), not by a global tenant filter, so one forgotten check on a
 * new endpoint is a silent cross-tenant hole. This suite is the regression net for that.
 *
 * MockMvc is wired manually for the same reason as StorePublishLifecycleIT (see its Javadoc).
 * Each test also gets its own client IP: RateLimitFilter keeps per-IP windows in a singleton bean
 * that outlives individual tests, and this suite makes far more dashboard writes than the
 * 120/minute limit allows from a single address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
@Transactional
class MultiTenantIsolationIT {
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;
    private String remoteAddr;

    private User staffA;
    private User ownerB;
    private String ownerAToken;
    private String ownerBToken;
    private String staffAToken;
    private String customerToken;

    private String slugA;
    private String slugB;
    private String storeA;
    private String storeB;

    private String categoryA;
    private String productA;
    private String productB;
    private String zoneA;
    private String offerA;
    private String slotA;
    private String slotB;
    private String appointmentA;
    private String orderA;
    private static final String ORDER_CODE_A = "AORD1234";

    @BeforeEach
    void setUp() throws Exception {
        Filter springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        int n = IP_COUNTER.incrementAndGet();
        remoteAddr = "10.77." + (n / 250) + "." + (n % 250 + 1);

        User ownerA = saveUser(Role.MERCHANT_OWNER, "owner-a");
        ownerB = saveUser(Role.MERCHANT_OWNER, "owner-b");
        User customer = saveUser(Role.CUSTOMER, "customer");
        ownerAToken = jwtService.createAccessToken(ownerA);
        ownerBToken = jwtService.createAccessToken(ownerB);
        customerToken = jwtService.createAccessToken(customer);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        slugA = "tenant-a-" + suffix;
        slugB = "tenant-b-" + suffix;

        // ── Tenant A: a fully populated store ────────────────────────────────────────────────
        storeA = idOf(send(POST, "/api/dashboard/stores", ownerAToken, storeBody(slugA, "DRAFT")).andExpect(status().isOk()));
        categoryA = idOf(send(POST, "/api/dashboard/categories", ownerAToken, categoryBody(storeA, "cat-a", null)).andExpect(status().isOk()));
        productA = idOf(send(POST, "/api/dashboard/products", ownerAToken, productBody(storeA, "prod-a", categoryA)).andExpect(status().isOk()));
        zoneA = idOf(send(POST, "/api/dashboard/delivery-zones", ownerAToken, zoneBody(storeA, "a")).andExpect(status().isOk()));
        offerA = idOf(send(POST, "/api/dashboard/offers", ownerAToken, offerBody(storeA, "SAVEA")).andExpect(status().isOk()));
        slotA = idOf(send(POST, "/api/dashboard/appointment-slots", ownerAToken, slotBody(storeA)).andExpect(status().isOk()));
        send(PUT, "/api/dashboard/theme-content", ownerAToken, themeBody(storeA, "Store A hero")).andExpect(status().isOk());
        send(POST, "/api/dashboard/theme-content/publish", ownerAToken, "{\"storeId\":\"" + storeA + "\"}").andExpect(status().isOk());
        send(PUT, "/api/dashboard/stores/" + storeA, ownerAToken, storeBody(slugA, "ACTIVE")).andExpect(status().isOk());

        // ── Tenant B: same shape, different owner ────────────────────────────────────────────
        storeB = idOf(send(POST, "/api/dashboard/stores", ownerBToken, storeBody(slugB, "DRAFT")).andExpect(status().isOk()));
        productB = idOf(send(POST, "/api/dashboard/products", ownerBToken, productBody(storeB, "prod-b", null)).andExpect(status().isOk()));
        send(POST, "/api/dashboard/offers", ownerBToken, offerBody(storeB, "SAVEB")).andExpect(status().isOk());
        slotB = idOf(send(POST, "/api/dashboard/appointment-slots", ownerBToken, slotBody(storeB)).andExpect(status().isOk()));
        send(PUT, "/api/dashboard/stores/" + storeB, ownerBToken, storeBody(slugB, "ACTIVE")).andExpect(status().isOk());

        // ── A's staff member (persisted directly — the invite flow sends email) ──────────────
        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        staffA = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staffA.setStore(storeAEntity);
        staffA = userRepository.save(staffA);
        staffAToken = jwtService.createAccessToken(staffA);

        // ── A's order (persisted directly — public checkout needs a customer + is covered below) ──
        Product productAEntity = productRepository.findById(UUID.fromString(productA)).orElseThrow();
        orderA = persistOrder(storeAEntity, productAEntity, ORDER_CODE_A).getId().toString();

        // ── A's appointment, booked through A's own public slug ──────────────────────────────
        appointmentA = idOf(send(POST, "/api/public/stores/" + slugA + "/appointments", customerToken, bookingBody(slotA))
                .andExpect(status().isOk()));
    }

    // ═════════════════════════════ store record (owner-only) ══════════════════════════════════

    @Test
    void foreignOwnerCannotUpdateOrDeleteAnotherOwnersStore() throws Exception {
        send(PUT, "/api/dashboard/stores/" + storeA, ownerBToken, storeBody(slugA, "SUSPENDED")).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/stores/" + storeA, ownerBToken, null).andExpect(status().isForbidden());

        send(GET, "/api/dashboard/stores/my", ownerAToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(storeA))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
        send(GET, "/api/dashboard/stores/my", ownerBToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + storeA + "')]").isEmpty());
    }

    // ═════════════════════════════ catalog ════════════════════════════════════════════════════

    @Test
    void foreignOwnerCannotCreateReadUpdateOrDeleteProductsInAnotherStore() throws Exception {
        send(POST, "/api/dashboard/products", ownerBToken, productBody(storeA, "sneaky", null)).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/products/" + productA, ownerBToken, null).andExpect(status().isForbidden());
        // Even a body that claims B's own store must not let B overwrite/move A's product.
        send(PUT, "/api/dashboard/products/" + productA, ownerBToken, productBody(storeB, "prod-a", null)).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/products/" + productA, ownerBToken, productBody(storeA, "prod-a", null)).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/products/" + productA, ownerBToken, null).andExpect(status().isForbidden());

        // …and A cannot push their own product into B's catalog either.
        send(PUT, "/api/dashboard/products/" + productA, ownerAToken, productBody(storeB, "prod-a", null)).andExpect(status().isForbidden());

        // Control: A still owns an untouched product.
        send(GET, "/api/dashboard/products/" + productA, ownerAToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(storeA))
                .andExpect(jsonPath("$.data.slug").value("prod-a"));
    }

    @Test
    void foreignOwnerCannotTouchAnotherStoresCategoriesOrAttachThemToTheirOwn() throws Exception {
        send(POST, "/api/dashboard/categories", ownerBToken, categoryBody(storeA, "sneaky", null)).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/categories/" + categoryA, ownerBToken, categoryBody(storeB, "cat-a", null)).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/categories/" + categoryA, ownerBToken, null).andExpect(status().isForbidden());

        // A merchant can't mint a global (store-less) category — that's a super-admin-only shape.
        send(POST, "/api/dashboard/categories", ownerBToken, categoryBody(null, "global-x", null)).andExpect(status().isForbidden());

        // Cross-store references: a category parented under A's, or a product filed under A's category.
        send(POST, "/api/dashboard/categories", ownerBToken, categoryBody(storeB, "child", categoryA)).andExpect(status().isForbidden());
        send(POST, "/api/dashboard/products", ownerBToken, productBody(storeB, "with-foreign-cat", categoryA)).andExpect(status().isForbidden());

        // Control: B can create a category + product against their own tenant.
        String ownCategory = idOf(send(POST, "/api/dashboard/categories", ownerBToken, categoryBody(storeB, "cat-b", null)).andExpect(status().isOk()));
        send(POST, "/api/dashboard/products", ownerBToken, productBody(storeB, "with-own-cat", ownCategory)).andExpect(status().isOk());
    }

    // ═════════════════════════════ list endpoints never leak ══════════════════════════════════

    @Test
    void listEndpointsNeverLeakForeignRowsEvenWhenAskedForAForeignStoreId() throws Exception {
        // Explicit foreign storeId → filtered against the caller's own stores → empty, never A's rows.
        for (String path : new String[]{
                "/api/dashboard/products", "/api/dashboard/orders", "/api/dashboard/offers",
                "/api/dashboard/appointment-slots", "/api/dashboard/appointments"}) {
            send(GET, path + "?storeId=" + storeA, ownerBToken, null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        // Unfiltered → only the caller's own tenant, with A's rows absent.
        send(GET, "/api/dashboard/products", ownerBToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(productB));
        send(GET, "/api/dashboard/categories", ownerBToken, null)
                .andExpect(jsonPath("$.data[?(@.storeId=='" + storeA + "')]").isEmpty());
        send(GET, "/api/dashboard/delivery-zones", ownerBToken, null)
                .andExpect(jsonPath("$.data[?(@.storeId=='" + storeA + "')]").isEmpty());
        send(GET, "/api/dashboard/offers", ownerBToken, null)
                .andExpect(jsonPath("$.data[?(@.storeId=='" + storeA + "')]").isEmpty());
        send(GET, "/api/dashboard/orders", ownerBToken, null)
                .andExpect(jsonPath("$.data[?(@.storeId=='" + storeA + "')]").isEmpty());

        // Control: the owning tenant sees their own rows through the very same endpoints.
        send(GET, "/api/dashboard/orders?storeId=" + storeA, ownerAToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(orderA));
        send(GET, "/api/dashboard/appointments?storeId=" + storeA, ownerAToken, null)
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    // ═════════════════════════════ delivery / offers / appointments ═══════════════════════════

    @Test
    void foreignOwnerCannotManageAnotherStoresDeliveryZones() throws Exception {
        send(POST, "/api/dashboard/delivery-zones", ownerBToken, zoneBody(storeA, "sneaky")).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/delivery-zones/" + zoneA, ownerBToken, zoneBody(storeB, "a")).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/delivery-zones/" + zoneA, ownerBToken, null).andExpect(status().isForbidden());

        send(GET, "/api/dashboard/delivery-zones", ownerAToken, null)
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(zoneA));
    }

    @Test
    void foreignOwnerCannotManageAnotherStoresOffers() throws Exception {
        send(POST, "/api/dashboard/offers", ownerBToken, offerBody(storeA, "SNEAKY")).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/offers/" + offerA, ownerBToken, offerBody(storeB, "SAVEA")).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/offers/" + offerA, ownerBToken, null).andExpect(status().isForbidden());

        send(GET, "/api/dashboard/offers?storeId=" + storeA, ownerAToken, null)
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("SAVEA"));
    }

    @Test
    void foreignOwnerCannotManageAnotherStoresAppointmentsOrSlots() throws Exception {
        send(POST, "/api/dashboard/appointment-slots", ownerBToken, slotBody(storeA)).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/appointment-slots/" + slotA, ownerBToken, null).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/appointments/" + appointmentA + "/status", ownerBToken, "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isForbidden());

        // The denied cancel changed nothing; the owner can still act on it.
        send(GET, "/api/dashboard/appointments?storeId=" + storeA, ownerAToken, null)
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));
        send(PUT, "/api/dashboard/appointments/" + appointmentA + "/status", ownerAToken, "{\"status\":\"COMPLETED\"}")
                .andExpect(status().isOk());
    }

    // ═════════════════════════════ orders, customers, analytics ═══════════════════════════════

    @Test
    void foreignOwnerCannotReadOrChangeAnotherStoresOrders() throws Exception {
        send(GET, "/api/dashboard/orders/" + orderA, ownerBToken, null).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/orders/" + orderA + "/status", ownerBToken, "{\"status\":\"CANCELLED\"}").andExpect(status().isForbidden());

        // The denied cancel must not have touched the order (or restored stock / adjusted sales).
        send(GET, "/api/dashboard/orders/" + orderA, ownerAToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEW"))
                .andExpect(jsonPath("$.data.storeId").value(storeA));
    }

    @Test
    void foreignOwnerCannotReadAnotherStoresCustomersOrAnalytics() throws Exception {
        // Inside OrderService.MAX_ANALYTICS_RANGE_DAYS (400) — the range check runs before the access
        // check, so an over-long range would 400 and mask the 403 this test is after.
        String range = "from=2026-01-01&to=2026-12-31";
        send(GET, "/api/dashboard/customers?storeId=" + storeA, ownerBToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeA + "&" + range, ownerBToken, null)
                .andExpect(status().isForbidden());
        send(GET, "/api/dashboard/analytics/daily-store-sales?storeId=" + storeA + "&" + range, ownerBToken, null)
                .andExpect(status().isForbidden());

        // Unscoped sales analytics only ever covers the caller's own stores.
        send(GET, "/api/dashboard/analytics/daily-store-sales?" + range, ownerBToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.storeId=='" + storeA + "')]").isEmpty());

        // Control: owners reach their own stores' analytics through the same endpoints. Only stores
        // that have no rows are used here — these are native queries, and on H2 their UUID/Instant
        // columns come back as byte[]/OffsetDateTime, which Spring Data's projections can't convert
        // (H2-only gap, fine on Postgres). An empty result never reaches that conversion, and the
        // 403s above are thrown before the query runs at all.
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeB + "&" + range, ownerBToken, null).andExpect(status().isOk());
        send(GET, "/api/dashboard/analytics/daily-store-sales?storeId=" + storeA + "&" + range, ownerAToken, null).andExpect(status().isOk());
    }

    // ═════════════════════════════ storefront theme content ═══════════════════════════════════

    @Test
    void foreignOwnerCannotReadEditPublishOrRestoreAnotherStoresThemeContent() throws Exception {
        send(GET, "/api/dashboard/theme-content?storeId=" + storeA, ownerBToken, null).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/theme-content", ownerBToken, themeBody(storeA, "defaced")).andExpect(status().isForbidden());
        send(POST, "/api/dashboard/theme-content/publish", ownerBToken, "{\"storeId\":\"" + storeA + "\"}").andExpect(status().isForbidden());
        send(GET, "/api/dashboard/theme-content/versions?storeId=" + storeA, ownerBToken, null).andExpect(status().isForbidden());
        send(POST, "/api/dashboard/theme-content/versions/restore", ownerBToken, "{\"storeId\":\"" + storeA + "\",\"version\":1}")
                .andExpect(status().isForbidden());

        send(GET, "/api/dashboard/theme-content?storeId=" + storeA, ownerAToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftContent.heroTitle").value("Store A hero"))
                .andExpect(jsonPath("$.data.publishedContent.heroTitle").value("Store A hero"))
                .andExpect(jsonPath("$.data.publishedVersion").value(1));
    }

    // ═════════════════════════════ staff ══════════════════════════════════════════════════════

    @Test
    void foreignOwnerCannotManageAnotherStoresStaff() throws Exception {
        String inviteBody = "{\"storeId\":\"" + storeA + "\",\"email\":\"new-" + UUID.randomUUID() + "@test.com\",\"fullName\":\"X\"}";
        send(POST, "/api/dashboard/staff/invite", ownerBToken, inviteBody).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/staff?storeId=" + storeA, ownerBToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/staff/" + staffA.getId() + "/permissions", ownerBToken, null).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/staff/" + staffA.getId() + "/permissions", ownerBToken,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"NONE\"}]}").andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/staff/" + staffA.getId() + "/deactivate", ownerBToken, null).andExpect(status().isForbidden());

        // A's staff member is unaffected.
        send(GET, "/api/dashboard/products", staffAToken, null).andExpect(status().isOk());
    }

    @Test
    void staffEndpointsAnswerAnotherMerchantsUserIdExactlyLikeAnUnknownId() throws Exception {
        // M2-14 regression: a non-staff user id used to answer 400 "Not a staff member" while an
        // unknown id answered 404, which let an owner probe which ids belong to real accounts.
        for (String target : new String[]{ownerB.getId().toString(), UUID.randomUUID().toString()}) {
            send(GET, "/api/dashboard/staff/" + target + "/permissions", ownerAToken, null).andExpect(status().isNotFound());
            send(PUT, "/api/dashboard/staff/" + target + "/permissions", ownerAToken,
                    "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"NONE\"}]}").andExpect(status().isNotFound());
            send(PUT, "/api/dashboard/staff/" + target + "/deactivate", ownerAToken, null).andExpect(status().isNotFound());
        }
        assertThat(userRepository.findById(ownerB.getId()).orElseThrow().isActive()).isTrue();
        // Positive control: A's own staff member is still reachable.
        send(GET, "/api/dashboard/staff/" + staffA.getId() + "/permissions", ownerAToken, null).andExpect(status().isOk());
    }

    @Test
    void staffAreConfinedToTheirOwnStoreAndCannotDoOwnerOnlyActions() throws Exception {
        // Inside their own store they work normally (grid defaults to EDIT).
        send(POST, "/api/dashboard/products", staffAToken, productBody(storeA, "staff-made", null)).andExpect(status().isOk());

        // Across the boundary: nothing.
        send(POST, "/api/dashboard/products", staffAToken, productBody(storeB, "sneaky", null)).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/products/" + productB, staffAToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/theme-content?storeId=" + storeB, staffAToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/customers?storeId=" + storeB, staffAToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/products?storeId=" + storeB, staffAToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        send(GET, "/api/dashboard/products", staffAToken, null)
                .andExpect(jsonPath("$.data[?(@.storeId=='" + storeB + "')]").isEmpty());

        // Owner-only: store record, and everything under /staff except "my own permissions".
        send(PUT, "/api/dashboard/stores/" + storeA, staffAToken, storeBody(slugA, "ACTIVE")).andExpect(status().isForbidden());
        send(DELETE, "/api/dashboard/stores/" + storeA, staffAToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/staff?storeId=" + storeA, staffAToken, null).andExpect(status().isForbidden());
        send(PUT, "/api/dashboard/staff/" + staffA.getId() + "/permissions", staffAToken,
                "{\"grants\":[{\"section\":\"STOREFRONT\",\"level\":\"EDIT\"}]}").andExpect(status().isForbidden());
        send(GET, "/api/dashboard/staff/me/permissions", staffAToken, null).andExpect(status().isOk());
    }

    @Test
    void storefrontSectionIsGovernedByTheStaffPermissionGrid() throws Exception {
        String themePath = "/api/dashboard/theme-content";
        String versionsPath = themePath + "/versions?storeId=" + storeA;

        // 1) No explicit row → defaults to EDIT, so staff keep the access they had before this
        //    section existed (the grid only ever removes access an owner dials down).
        send(GET, themePath + "?storeId=" + storeA, staffAToken, null).andExpect(status().isOk());
        send(PUT, themePath, staffAToken, themeBody(storeA, "staff edit")).andExpect(status().isOk());

        // 2) VIEW → can look (draft + history) but not change or publish anything.
        setStaffStorefrontLevel("VIEW");
        send(GET, themePath + "?storeId=" + storeA, staffAToken, null).andExpect(status().isOk());
        send(GET, versionsPath, staffAToken, null).andExpect(status().isOk());
        send(PUT, themePath, staffAToken, themeBody(storeA, "view-level defacement")).andExpect(status().isForbidden());
        send(POST, themePath + "/publish", staffAToken, "{\"storeId\":\"" + storeA + "\"}").andExpect(status().isForbidden());
        send(POST, themePath + "/versions/restore", staffAToken, "{\"storeId\":\"" + storeA + "\",\"version\":1}")
                .andExpect(status().isForbidden());
        send(GET, themePath + "?storeId=" + storeA, ownerAToken, null)
                .andExpect(jsonPath("$.data.draftContent.heroTitle").value("staff edit"))
                .andExpect(jsonPath("$.data.publishedVersion").value(1));

        // 3) NONE → can't even read.
        setStaffStorefrontLevel("NONE");
        send(GET, themePath + "?storeId=" + storeA, staffAToken, null).andExpect(status().isForbidden());
        send(GET, versionsPath, staffAToken, null).andExpect(status().isForbidden());
        send(PUT, themePath, staffAToken, themeBody(storeA, "none-level defacement")).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/staff/me/permissions", staffAToken, null)
                .andExpect(jsonPath("$.data[?(@.section=='STOREFRONT')].level", contains("NONE")))
                .andExpect(jsonPath("$.data[?(@.section=='PRODUCTS')].level", contains("EDIT")));

        // 4) The restriction is scoped to that one section, and never touches the owner.
        send(POST, "/api/dashboard/products", staffAToken, productBody(storeA, "still-allowed", null)).andExpect(status().isOk());
        send(PUT, themePath, ownerAToken, themeBody(storeA, "owner edit")).andExpect(status().isOk());
    }

    @Test
    void deactivatingStaffRevokesTheirAccessImmediately() throws Exception {
        send(GET, "/api/dashboard/products", staffAToken, null).andExpect(status().isOk());
        send(PUT, "/api/dashboard/staff/" + staffA.getId() + "/deactivate", ownerAToken, null).andExpect(status().isOk());
        // Same, still-unexpired access token — rejected as unauthenticated on the very next call.
        send(GET, "/api/dashboard/products", staffAToken, null).andExpect(status().isUnauthorized());
    }

    // ═════════════════════════════ public storefront surface ══════════════════════════════════

    @Test
    void publicEndpointsNeverCrossStoreBoundaries() throws Exception {
        String publicB = "/api/public/stores/" + slugB;

        // Cart injection: B's checkout must not accept A's product. Control: B's own product works.
        send(POST, publicB + "/orders", customerToken, orderBody(productA)).andExpect(status().isNotFound());
        send(POST, publicB + "/orders", customerToken, orderBody(productB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(storeB));

        // Booking injection: B's booking endpoint must not accept A's slot. Control: B's own works.
        send(POST, publicB + "/appointments", customerToken, bookingBody(slotA)).andExpect(status().isNotFound());
        send(POST, publicB + "/appointments", customerToken, bookingBody(slotB)).andExpect(status().isOk());

        // Discount codes are per store. Control: B's own code validates.
        send(POST, publicB + "/offers/validate", null, "{\"code\":\"SAVEA\",\"subtotal\":50}").andExpect(status().isBadRequest());
        send(POST, publicB + "/offers/validate", null, "{\"code\":\"SAVEB\",\"subtotal\":50}").andExpect(status().isOk());

        // Order lookup by code is scoped to the slug it's asked under. Control: A's own slug finds it.
        String lookup = "{\"orderCode\":\"" + ORDER_CODE_A + "\",\"email\":\"buyer@test.com\"}";
        send(POST, publicB + "/orders/lookup", null, lookup).andExpect(status().isNotFound());
        send(POST, "/api/public/stores/" + slugA + "/orders/lookup", null, lookup)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderCode").value(ORDER_CODE_A))
                .andExpect(jsonPath("$.data.storeId").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.customerEmail").doesNotExist())
                .andExpect(jsonPath("$.data.customerPhone").doesNotExist())
                .andExpect(jsonPath("$.data.customerAddress").doesNotExist())
                .andExpect(jsonPath("$.data.notes").doesNotExist());

        // Catalog reads: A's product slug doesn't resolve under B, and B's listing excludes it.
        send(GET, publicB + "/products/prod-a", null, null).andExpect(status().isNotFound());
        send(GET, publicB + "/products/prod-b", null, null).andExpect(status().isOk());
        send(GET, publicB + "/products", null, null)
                .andExpect(jsonPath("$.data[*].slug", hasItem("prod-b")))
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("prod-a"))));
    }

    @Test
    void storesThatAreNotActiveAreNotPubliclyReachable() throws Exception {
        String slugC = "tenant-c-" + UUID.randomUUID().toString().substring(0, 8);
        String storeC = idOf(send(POST, "/api/dashboard/stores", ownerAToken, storeBody(slugC, "DRAFT")).andExpect(status().isOk()));
        send(PUT, "/api/dashboard/theme-content", ownerAToken, themeBody(storeC, "unfinished")).andExpect(status().isOk());
        send(POST, "/api/dashboard/theme-content/publish", ownerAToken, "{\"storeId\":\"" + storeC + "\"}").andExpect(status().isOk());

        String publicC = "/api/public/stores/" + slugC;
        send(GET, publicC, null, null).andExpect(status().isNotFound());
        send(GET, publicC + "/homepage", null, null).andExpect(status().isNotFound());
        send(GET, publicC + "/products", null, null).andExpect(status().isNotFound());
        send(GET, publicC + "/categories", null, null).andExpect(status().isNotFound());
        send(GET, publicC + "/theme-content", null, null).andExpect(status().isNotFound());
        send(GET, publicC + "/appointment-slots", null, null).andExpect(status().isNotFound());
        send(POST, publicC + "/offers/validate", null, "{\"code\":\"X\",\"subtotal\":1}").andExpect(status().isNotFound());

        // Control: an ACTIVE store serves its published theme content publicly.
        send(GET, "/api/public/stores/" + slugA + "/theme-content", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heroTitle").value("Store A hero"));
    }

    // ═════════════════════════════ role boundaries ════════════════════════════════════════════

    @Test
    void dashboardAndAdminSurfacesRejectAnonymousAndWrongRoleCallers() throws Exception {
        send(GET, "/api/dashboard/products", null, null).andExpect(status().isUnauthorized());
        send(GET, "/api/dashboard/products", customerToken, null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/orders/" + orderA, customerToken, null).andExpect(status().isForbidden());

        send(GET, "/api/admin/security/login-attempts", ownerAToken, null).andExpect(status().isForbidden());
        send(GET, "/api/admin/security/login-attempts", staffAToken, null).andExpect(status().isForbidden());
        send(GET, "/api/admin/security/login-attempts", customerToken, null).andExpect(status().isForbidden());

        // M2-14 regression: URL-level role denials are written directly as 403 JSON. The default
        // handler's sendError re-dispatched to /error, where the JWT filter does not run, so on a real
        // server the caller looked anonymous and got 401 (MockMvc skips that dispatch; the missing
        // body is what gives the old behaviour away here).
        String adminToken = jwtService.createAccessToken(saveUser(Role.SUPER_ADMIN, "wrong-surface-admin"));
        for (String[] call : new String[][]{{adminToken, "/api/dashboard/stores/my"}, {adminToken, "/api/public/customers/me"},
                {ownerAToken, "/api/public/customers/me"}, {customerToken, "/api/dashboard/products"}}) {
            send(GET, call[1], call[0], null).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access denied"));
        }

        // M2-14 regression: READ_ONLY_ADMIN is refused SUPER_ADMIN writes (403), and `permanent` is
        // optional — it used to be a primitive, so any body omitting it was a 400 for every admin.
        String readOnlyToken = jwtService.createAccessToken(saveUser(Role.READ_ONLY_ADMIN, "read-only-admin"));
        String superToken = jwtService.createAccessToken(saveUser(Role.SUPER_ADMIN, "super-admin"));
        String blockBody = "{\"ipAddress\":\"203.0.113.77\",\"reason\":\"m2-14\"}";
        send(GET, "/api/admin/security/login-attempts", readOnlyToken, null).andExpect(status().isOk());
        send(POST, "/api/admin/security/block-ip", readOnlyToken, blockBody).andExpect(status().isForbidden());
        send(POST, "/api/admin/security/block-ip", ownerAToken, blockBody).andExpect(status().isForbidden());
        send(POST, "/api/admin/security/block-ip", superToken, blockBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permanent").value(false));

        // Since M1-06 guest checkout, order creation is open to anyone; a non-customer principal is
        // treated as a guest (CurrentUserService.customerOrNull) — never linked to the merchant.
        String ownerOrder = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", ownerAToken, orderBody(productA)).andExpect(status().isOk()));
        String staffOrder = idOf(send(POST, "/api/public/stores/" + slugA + "/orders", staffAToken, orderBody(productA)).andExpect(status().isOk()));
        for (String id : new String[] {ownerOrder, staffOrder}) {
            org.junit.jupiter.api.Assertions.assertNull(orderRepository.findById(UUID.fromString(id)).orElseThrow().getCustomer());
        }
    }

    // ═════════════════════════════ helpers ════════════════════════════════════════════════════

    private void setStaffStorefrontLevel(String level) throws Exception {
        send(PUT, "/api/dashboard/staff/" + staffA.getId() + "/permissions", ownerAToken,
                "{\"grants\":[{\"section\":\"STOREFRONT\",\"level\":\"" + level + "\"}]}").andExpect(status().isOk());
    }

    private ResultActions send(HttpMethod method, String path, String token, String body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path).with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        });
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(req);
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

    private CustomerOrder persistOrder(Store store, Product product, String orderCode) {
        CustomerOrder order = new CustomerOrder();
        order.setStore(store);
        order.setOrderCode(orderCode);
        order.setCustomerName("Test Customer");
        order.setCustomerEmail("buyer@test.com");
        order.setCustomerPhone("+962790000000");
        order.setDeliveryMethod(DeliveryMethod.PICKUP);
        order.setPaymentMethod(PaymentMethod.CASH);
        order.setSubtotal(new BigDecimal("10.000"));
        order.setTotal(new BigDecimal("10.000"));

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductNameSnapshot(product.getNameEn());
        item.setUnitPrice(new BigDecimal("10.000"));
        item.setQuantity(1);
        item.setTotal(new BigDecimal("10.000"));
        order.getItems().add(item);
        return orderRepository.save(order);
    }

    private static String storeBody(String slug, String status) {
        return "{\"name\":\"Store %s\",\"slug\":\"%s\",\"categorySlug\":\"general-store\",\"status\":\"%s\"}"
                .formatted(slug, slug, status);
    }

    private static String productBody(String storeId, String slug, String categoryId) {
        String category = categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"";
        return "{\"storeId\":\"%s\",\"nameEn\":\"Item %s\",\"slug\":\"%s\",\"price\":10.00,\"sortOrder\":0%s}"
                .formatted(storeId, slug, slug, category);
    }

    private static String categoryBody(String storeId, String slug, String parentId) {
        StringBuilder sb = new StringBuilder("{\"nameEn\":\"Cat ").append(slug)
                .append("\",\"slug\":\"").append(slug)
                .append("\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0");
        if (storeId != null) {
            sb.append(",\"storeId\":\"").append(storeId).append('"');
        }
        if (parentId != null) {
            sb.append(",\"parentId\":\"").append(parentId).append('"');
        }
        return sb.append('}').toString();
    }

    private static String zoneBody(String storeId, String name) {
        return "{\"storeId\":\"%s\",\"name\":\"Zone %s\",\"areas\":[\"Downtown\"],\"deliveryFee\":1.5}".formatted(storeId, name);
    }

    private static String offerBody(String storeId, String code) {
        return "{\"storeId\":\"%s\",\"code\":\"%s\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10}".formatted(storeId, code);
    }

    private static String slotBody(String storeId) {
        return "{\"storeId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"capacity\":2}"
                .formatted(storeId, isoInHours(48), isoInHours(49));
    }

    private static String isoInHours(int hours) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(ZoneOffset.UTC).plusHours(hours).withNano(0));
    }

    private static String themeBody(String storeId, String heroTitle) {
        return "{\"storeId\":\"%s\",\"content\":{\"heroTitle\":\"%s\"}}".formatted(storeId, heroTitle);
    }

    private static String orderBody(String productId) {
        return ("{\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"%s\",\"quantity\":1}]}").formatted(productId);
    }

    private static String bookingBody(String slotId) {
        return "{\"slotId\":\"%s\",\"customerName\":\"Cust\",\"customerPhone\":\"+962790000000\"}".formatted(slotId);
    }
}
