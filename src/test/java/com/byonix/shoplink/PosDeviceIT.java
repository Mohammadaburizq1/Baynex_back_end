package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.PosDeviceRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-02 device activation and POS-04 device-scoped catalog sync, over real HTTP (MockMvc + full
 * security chain) on PostgreSQL. The central property: a device credential reads exactly one
 * store — the one it was registered to — and nothing else in the API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
@Transactional
class PosDeviceIT {
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired PosDeviceRepository deviceRepository;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;
    private String remoteAddr;
    private String ownerAToken;
    private String ownerBToken;
    private String staffAToken;
    private String storeA;
    private String storeB;
    private String categoryA;
    private String burgerA;
    private String shakeA;
    private String productB;

    @BeforeEach
    void setUp() throws Exception {
        Filter springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(springSecurityFilterChain).build();
        int n = IP_COUNTER.incrementAndGet();
        remoteAddr = "10.88." + (n / 250) + "." + (n % 250 + 1);

        User ownerA = saveUser(Role.MERCHANT_OWNER, "pos-owner-a");
        User ownerB = saveUser(Role.MERCHANT_OWNER, "pos-owner-b");
        ownerAToken = jwtService.createAccessToken(ownerA);
        ownerBToken = jwtService.createAccessToken(ownerB);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        storeA = idOf(send(POST, "/api/dashboard/stores", bearer(ownerAToken),
                "{\"name\":\"POS Cafe\",\"slug\":\"pos-a-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\",\"timezone\":\"Asia/Amman\"}")
                .andExpect(status().isOk()));
        categoryA = idOf(send(POST, "/api/dashboard/categories", bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Mains\",\"slug\":\"mains\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0}")
                .andExpect(status().isOk()));
        burgerA = idOf(send(POST, "/api/dashboard/products", bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"categoryId\":\"" + categoryA + "\",\"nameEn\":\"Burger\",\"slug\":\"burger\",\"sku\":\"BRG-1\",\"price\":5.250,\"stock\":7,\"sortOrder\":0}")
                .andExpect(status().isOk()));
        send(PUT, "/api/dashboard/products/" + burgerA + "/modifier-groups", bearer(ownerAToken),
                "{\"groups\":[{\"name\":\"Extras\",\"minSelect\":0,\"maxSelect\":2,\"options\":[{\"name\":\"Cheese\",\"priceDelta\":0.5,\"preselected\":false,\"available\":true}]}]}")
                .andExpect(status().isOk());
        shakeA = idOf(send(POST, "/api/dashboard/products", bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"categoryId\":\"" + categoryA + "\",\"nameEn\":\"Shake\",\"slug\":\"shake\",\"price\":3,\"sortOrder\":1}")
                .andExpect(status().isOk()));
        send(PUT, "/api/dashboard/products/" + shakeA + "/variants", bearer(ownerAToken),
                "{\"options\":[{\"name\":\"Size\",\"values\":[{\"label\":\"S\"},{\"label\":\"M\"}]}],"
                        + "\"variants\":[{\"selection\":[\"S\"],\"sku\":\"SH-S-" + suffix + "\",\"price\":3,\"stock\":4,\"available\":true},"
                        + "{\"selection\":[\"M\"],\"sku\":\"SH-M-" + suffix + "\",\"price\":3.5,\"stock\":0,\"available\":true}]}")
                .andExpect(status().isOk());

        storeB = idOf(send(POST, "/api/dashboard/stores", bearer(ownerBToken),
                "{\"name\":\"Other Shop\",\"slug\":\"pos-b-" + suffix + "\",\"categorySlug\":\"general-store\"}")
                .andExpect(status().isOk()));
        productB = idOf(send(POST, "/api/dashboard/products", bearer(ownerBToken),
                "{\"storeId\":\"" + storeB + "\",\"nameEn\":\"B Secret Item\",\"slug\":\"b-item\",\"price\":9,\"stock\":3,\"sortOrder\":0}")
                .andExpect(status().isOk()));

        User staffA = saveUser(Role.MERCHANT_STAFF, "pos-staff-a");
        staffA.setStore(storeRepository.findById(UUID.fromString(storeA)).orElseThrow());
        staffAToken = jwtService.createAccessToken(userRepository.save(staffA));
    }

    @Test
    void ownerRegistersDeviceAndActivationYieldsAStoreScopedCredentialStoredOnlyAsAHash() throws Exception {
        String body = send(POST, "/api/dashboard/pos-devices", bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"name\":\"Main Counter\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.data.activationCode");
        String deviceId = JsonPath.read(body, "$.data.device.id");
        assertThat(code).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}");

        PosDevice pending = deviceRepository.findById(UUID.fromString(deviceId)).orElseThrow();
        assertThat(pending.getActivationCodeHash()).isNotNull().doesNotContain(code.replace("-", ""));

        // Lower-case and without the dash still activates (typed by hand on a tablet).
        String activation = send(POST, "/api/pos/activate", null, activateBody(code.replace("-", "").toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store.id").value(storeA))
                .andExpect(jsonPath("$.data.store.currency").value("JOD"))
                .andExpect(jsonPath("$.data.store.email").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String credential = JsonPath.read(activation, "$.data.deviceCredential");
        assertThat(credential).startsWith("kgpos_").hasSizeGreaterThan(60);

        PosDevice active = deviceRepository.findById(UUID.fromString(deviceId)).orElseThrow();
        assertThat(active.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(active.getCredentialHash()).isNotNull().isNotEqualTo(credential).doesNotContain(credential.substring(6));
        assertThat(active.getActivationCodeHash()).isNull();
        assertThat(active.getCredentialExpiresAt()).isAfter(Instant.now().plusSeconds(29L * 86400));
        assertThat(active.getPlatform()).isEqualTo("windows");

        // Single use.
        send(POST, "/api/pos/activate", null, activateBody(code)).andExpect(status().isBadRequest());
        send(GET, "/api/pos/device", device(credential), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceName").value("Main Counter"));
    }

    @Test
    void catalogCarriesStoreConfigCategoriesProductsVariantsAddOnsAndExactStock() throws Exception {
        String credential = activate(storeA, ownerAToken);
        String body = send(GET, "/api/pos/catalog", device(credential), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unchanged").value(false))
                .andExpect(jsonPath("$.data.store.id").value(storeA))
                .andExpect(jsonPath("$.data.store.timezone").value("Asia/Amman"))
                .andExpect(jsonPath("$.data.categories[*].id", hasItem(categoryA)))
                .andExpect(jsonPath("$.data.products", hasSize(2)))
                .andExpect(jsonPath("$.data.products[?(@.id=='" + burgerA + "')].stock").value(7))
                .andExpect(jsonPath("$.data.products[?(@.id=='" + burgerA + "')].sku").value("BRG-1"))
                .andExpect(jsonPath("$.data.products[?(@.id=='" + burgerA + "')].modifierGroups[0].options[0].name").value("Cheese"))
                .andExpect(jsonPath("$.data.products[?(@.id=='" + shakeA + "')].variants[*].stock", org.hamcrest.Matchers.containsInAnyOrder(4, 0)))
                .andExpect(jsonPath("$.data.products[?(@.id=='" + shakeA + "')].options[0].name").value("Size"))
                .andReturn().getResponse().getContentAsString();
        String version = JsonPath.read(body, "$.data.catalogVersion");

        // Unchanged catalog: no payload.
        send(GET, "/api/pos/catalog?knownVersion=" + version, device(credential), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unchanged").value(true))
                .andExpect(jsonPath("$.data.products").doesNotExist());

        // Disabling a product and a category on the server removes them from the next snapshot.
        send(PUT, "/api/dashboard/products/" + shakeA, bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"categoryId\":\"" + categoryA + "\",\"nameEn\":\"Shake\",\"slug\":\"shake\",\"price\":3,\"sortOrder\":1,\"available\":false}")
                .andExpect(status().isOk());
        send(PUT, "/api/dashboard/categories/" + categoryA, bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Mains\",\"slug\":\"mains\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0,\"active\":false}")
                .andExpect(status().isOk());
        send(GET, "/api/pos/catalog?knownVersion=" + version, device(credential), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unchanged").value(false))
                .andExpect(jsonPath("$.data.catalogVersion").value(not(version)))
                .andExpect(jsonPath("$.data.categories", hasSize(0)))
                .andExpect(jsonPath("$.data.products[*].id", not(hasItem(shakeA))));
    }

    @Test
    void aDeviceReadsOnlyItsOwnStoreAndCannotUseAnyOtherApi() throws Exception {
        String credentialA = activate(storeA, ownerAToken);
        String credentialB = activate(storeB, ownerBToken);

        // A storeId parameter is ignored: the store comes from the device record.
        String a = send(GET, "/api/pos/catalog?storeId=" + storeB, device(credentialA), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.store.id").value(storeA))
                .andReturn().getResponse().getContentAsString();
        assertThat(a).doesNotContain(storeB).doesNotContain(productB).doesNotContain("B Secret Item");
        send(GET, "/api/pos/catalog", device(credentialB), null)
                .andExpect(jsonPath("$.data.store.id").value(storeB))
                .andExpect(jsonPath("$.data.products[*].id", not(hasItem(burgerA))));

        // The device credential is not accepted by any merchant, customer or admin API.
        for (String path : List.of("/api/dashboard/products?storeId=" + storeB, "/api/dashboard/stores/my",
                "/api/dashboard/products/" + productB, "/api/public/customers/me", "/api/admin/security/login-attempts")) {
            int code = send(GET, path, device(credentialA), null).andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(401, 403);
            int asBearer = send(GET, path, bearer(credentialA), null).andReturn().getResponse().getStatus();
            assertThat(asBearer).as("bearer " + path).isIn(401, 403);
        }
        // ...and a merchant login is not a device.
        send(GET, "/api/pos/catalog", bearer(ownerAToken), null).andExpect(status().isForbidden());
        send(GET, "/api/pos/catalog", null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheOwningMerchantManagesAStoresDevices() throws Exception {
        send(POST, "/api/dashboard/pos-devices", bearer(ownerBToken), "{\"storeId\":\"" + storeA + "\",\"name\":\"Sneaky\"}")
                .andExpect(status().isForbidden());
        send(POST, "/api/dashboard/pos-devices", bearer(staffAToken), "{\"storeId\":\"" + storeA + "\",\"name\":\"Staff\"}")
                .andExpect(status().isForbidden());
        String deviceId = JsonPath.read(send(POST, "/api/dashboard/pos-devices", bearer(ownerAToken),
                "{\"storeId\":\"" + storeA + "\",\"name\":\"Counter 2\"}").andReturn().getResponse().getContentAsString(), "$.data.device.id");
        send(GET, "/api/dashboard/pos-devices?storeId=" + storeA, bearer(ownerBToken), null).andExpect(status().isForbidden());
        send(POST, "/api/dashboard/pos-devices/" + deviceId + "/revoke", bearer(ownerBToken), null).andExpect(status().isForbidden());
        send(POST, "/api/dashboard/pos-devices/" + deviceId + "/activation-code", bearer(ownerBToken), null).andExpect(status().isForbidden());
        send(GET, "/api/dashboard/pos-devices?storeId=" + storeA, bearer(ownerAToken), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].name", hasItem("Counter 2")))
                .andExpect(jsonPath("$.data[0].activationCode").doesNotExist());
    }

    @Test
    void revokedExpiredAndUnknownCredentialsAreRejectedWithDistinctCodes() throws Exception {
        String credential = activate(storeA, ownerAToken);
        PosDevice device = deviceRepository.findAll().stream()
                .filter(d -> d.getStore().getId().toString().equals(storeA)).findFirst().orElseThrow();

        send(GET, "/api/pos/catalog", device("kgpos_not-a-real-credential"), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("POS_DEVICE_INVALID"));

        device.setCredentialExpiresAt(Instant.now().minusSeconds(5));
        deviceRepository.saveAndFlush(device);
        send(GET, "/api/pos/catalog", device(credential), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("POS_DEVICE_EXPIRED"));

        // Re-pairing issues a new code; the new credential replaces the old one.
        String code = JsonPath.read(send(POST, "/api/dashboard/pos-devices/" + device.getId() + "/activation-code", bearer(ownerAToken), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.activationCode");
        String renewed = JsonPath.read(send(POST, "/api/pos/activate", null, activateBody(code))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.deviceCredential");
        send(GET, "/api/pos/catalog", device(renewed), null).andExpect(status().isOk());
        send(GET, "/api/pos/catalog", device(credential), null).andExpect(status().isUnauthorized());

        send(POST, "/api/dashboard/pos-devices/" + device.getId() + "/revoke", bearer(ownerAToken), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVOKED"));
        // Revocation clears the credential hash, so the credential simply no longer exists.
        send(GET, "/api/pos/catalog", device(renewed), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("POS_DEVICE_INVALID"));
        send(POST, "/api/dashboard/pos-devices/" + device.getId() + "/activation-code", bearer(ownerAToken), null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongOrExpiredActivationCodesFailIdentically() throws Exception {
        String body = send(POST, "/api/dashboard/pos-devices", bearer(ownerAToken), "{\"storeId\":\"" + storeA + "\",\"name\":\"Tablet\"}")
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.data.activationCode");
        String wrong = send(POST, "/api/pos/activate", null, activateBody("ZZZZZ-ZZZZZ"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        PosDevice device = deviceRepository.findById(UUID.fromString(JsonPath.read(body, "$.data.device.id"))).orElseThrow();
        device.setActivationCodeExpiresAt(Instant.now().minusSeconds(1));
        deviceRepository.saveAndFlush(device);
        String expired = send(POST, "/api/pos/activate", null, activateBody(code))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(expired).isEqualTo(wrong);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private String activate(String storeId, String ownerToken) throws Exception {
        String code = JsonPath.read(send(POST, "/api/dashboard/pos-devices", bearer(ownerToken),
                "{\"storeId\":\"" + storeId + "\",\"name\":\"Counter\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.activationCode");
        return JsonPath.read(send(POST, "/api/pos/activate", null, activateBody(code)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.deviceCredential");
    }

    private static String activateBody(String code) {
        return "{\"activationCode\":\"" + code + "\",\"installationId\":\"" + UUID.randomUUID() + "\",\"platform\":\"windows\",\"appVersion\":\"0.1.0\"}";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String device(String credential) {
        return "PosDevice " + credential;
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
