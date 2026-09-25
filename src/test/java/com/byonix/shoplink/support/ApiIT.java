package com.byonix.shoplink.support;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared plumbing for the Catalog integration tests: real HTTP through the real security filter
 * chain, controllers, services and JPA against H2 (application-test.yml), rolled back per test.
 * MockMvc is wired manually for the reason given in StorePublishLifecycleIT's Javadoc.
 *
 * Every test method gets its own client IP: RateLimitFilter keeps per-IP windows in a singleton
 * bean that outlives a test, and these suites make more dashboard writes than the 120/minute
 * limit allows from a single address.
 *
 * All classes extending this share one Spring context (same annotations + profile), so adding a
 * subclass doesn't cost another application start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@Transactional
public abstract class ApiIT {
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired protected WebApplicationContext webApplicationContext;
    @Autowired protected UserRepository userRepository;
    @Autowired protected StoreRepository storeRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected JwtService jwtService;
    @PersistenceContext protected EntityManager entityManager;

    protected MockMvc mockMvc;
    private String remoteAddr;

    @BeforeEach
    void initMockMvc() {
        Filter springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        int n = IP_COUNTER.incrementAndGet();
        remoteAddr = "10.88." + (n / 250) + "." + (n % 250 + 1);
    }

    // ── requests ────────────────────────────────────────────────────────────────────────────

    protected ResultActions send(HttpMethod method, String path, String token, String body) throws Exception {
        // The whole test runs in one transaction, but in production every request is its own
        // transaction with its own persistence context. Emulate that boundary: write out whatever
        // the previous call left pending (otherwise a bulk UPDATE with clearAutomatically, such as
        // the stock decrement, silently discards a not-yet-flushed insert) and start each request
        // from a clean session, so it reads what the database holds — e.g. the NULL that
        // ON DELETE SET NULL wrote — rather than stale managed entities.
        entityManager.flush();
        entityManager.clear();
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

    /** A multipart image upload (same fresh-session behaviour as {@link #send}). */
    protected ResultActions upload(String token, String storeId, String fileName, String contentType, byte[] bytes) throws Exception {
        entityManager.flush();
        entityManager.clear();
        MockMultipartHttpServletRequestBuilder req = multipart("/api/dashboard/media/images");
        req.file(new MockMultipartFile("file", fileName, contentType, bytes));
        req.param("storeId", storeId);
        req.with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        });
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(req);
    }

    protected static String idOf(ResultActions result) throws Exception {
        return read(result, "$.data.id");
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    /** First hit of a path that may be definite (a scalar) or a filter/wildcard (a list), as text. */
    protected static String firstOf(ResultActions result, String jsonPath) throws Exception {
        Object hit = read(result, jsonPath);
        return (hit instanceof List<?> list ? list.get(0) : hit).toString();
    }

    /**
     * Money assertions that don't care how the serializer happened to print the number (2, 2.0 and
     * 2.000 are the same amount) or whether the path is definite or a single-hit filter.
     */
    protected static ResultMatcher number(String jsonPath, double expected) {
        return result -> {
            Object actual = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
            if (actual instanceof List<?> list) {
                assertEquals(1, list.size(), "Expected exactly one hit for " + jsonPath);
                actual = list.get(0);
            }
            assertTrue(actual instanceof Number, jsonPath + " is not a number: " + actual);
            assertEquals(expected, ((Number) actual).doubleValue(), 0.0005, "JSON path " + jsonPath);
        };
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    protected User saveUser(Role role, String label) {
        User user = new User();
        user.setFullName(label);
        user.setEmail(label + "-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(role);
        user.setActive(true);
        return userRepository.save(user);
    }

    protected String tokenFor(User user) {
        return jwtService.createAccessToken(user);
    }

    protected static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a DRAFT store owned by the token's merchant and returns its id. */
    protected String createStore(String token, String slug) throws Exception {
        return idOf(send(HttpMethod.POST, "/api/dashboard/stores", token, storeBody(slug, "DRAFT")).andExpect(status().isOk()));
    }

    /** Publishes a store (it must already have at least one product). */
    protected void activateStore(String token, String storeId, String slug) throws Exception {
        send(HttpMethod.PUT, "/api/dashboard/stores/" + storeId, token, storeBody(slug, "ACTIVE")).andExpect(status().isOk());
    }

    // ── request bodies ──────────────────────────────────────────────────────────────────────

    protected static String storeBody(String slug, String status) {
        return "{\"name\":\"Store %s\",\"slug\":\"%s\",\"categorySlug\":\"general-store\",\"status\":\"%s\"}"
                .formatted(slug, slug, status);
    }

    protected static String productBody(String storeId, String slug, String categoryId) {
        return productBody(storeId, slug, categoryId, null, null);
    }

    protected static String productBody(String storeId, String slug, String categoryId, String sku, Integer stock) {
        StringBuilder sb = new StringBuilder("{\"storeId\":\"").append(storeId)
                .append("\",\"nameEn\":\"Item ").append(slug)
                .append("\",\"slug\":\"").append(slug)
                .append("\",\"price\":10.00,\"sortOrder\":0");
        if (categoryId != null) {
            sb.append(",\"categoryId\":\"").append(categoryId).append('"');
        }
        if (sku != null) {
            sb.append(",\"sku\":\"").append(sku).append('"');
        }
        if (stock != null) {
            sb.append(",\"stock\":").append(stock);
        }
        return sb.append('}').toString();
    }

    protected static String categoryBody(String storeId, String slug, String parentId) {
        return categoryBody(storeId, slug, parentId, true);
    }

    protected static String categoryBody(String storeId, String slug, String parentId, boolean active) {
        StringBuilder sb = new StringBuilder("{\"nameEn\":\"Cat ").append(slug)
                .append("\",\"slug\":\"").append(slug)
                .append("\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0,\"active\":").append(active);
        if (storeId != null) {
            sb.append(",\"storeId\":\"").append(storeId).append('"');
        }
        if (parentId != null) {
            sb.append(",\"parentId\":\"").append(parentId).append('"');
        }
        return sb.append('}').toString();
    }
}
