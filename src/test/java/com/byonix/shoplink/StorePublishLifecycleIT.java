package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end coverage of the publish gate over real HTTP + a real database (H2, via
 * application-test.yml): a merchant creates a store (server-forced DRAFT), tries to publish it
 * empty (rejected with the real product-count error), adds a product, publishes successfully,
 * then deletes that product and the store is demoted back to DRAFT automatically.
 * StoreServicePublishGateTest covers the same rules at the unit level with mocks; this proves
 * they also hold through real Flyway migrations, controllers, validation, security, and JPA —
 * the thing that was previously only reachable via a live Postgres/docker-compose instance.
 *
 * MockMvc is wired manually (webAppContextSetup + the springSecurityFilterChain bean) instead
 * of @AutoConfigureMockMvc / spring-security-test's springSecurity() configurer — neither
 * resolves in this project's Spring Boot 4 dependency set in offline mode. See README's
 * "Running integration tests" section.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
@Transactional
class StorePublishLifecycleIT {
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        Filter springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();

        User owner = new User();
        owner.setFullName("Lifecycle Test Owner");
        owner.setEmail("lifecycle-" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("irrelevant-for-this-test");
        owner.setRole(Role.MERCHANT_OWNER);
        owner.setActive(true);
        owner = userRepository.save(owner);
        token = jwtService.createAccessToken(owner);
    }

    @Test
    void createPublishBlockedAddProductPublishSucceedsThenRevertsOnLastProductRemoved() throws Exception {
        String slug = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8);

        // 1) Create — always DRAFT, even though the request asks for ACTIVE.
        String createBody = """
                {"name":"Lifecycle Store","slug":"%s","categorySlug":"general-store","status":"ACTIVE"}
                """.formatted(slug);
        String createJson = mockMvc.perform(post("/api/dashboard/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                // Not isCreated(): DashboardController returns a plain ApiResponse (no
                // @ResponseStatus), so "Created" is only a cosmetic message field — the actual
                // HTTP status is 200, same as every other success response in this API.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String storeId = JsonPath.read(createJson, "$.data.id");

        // 2) Publish attempt fails — zero products.
        String publishBody = """
                {"name":"Lifecycle Store","slug":"%s","categorySlug":"general-store","status":"ACTIVE"}
                """.formatted(slug);
        mockMvc.perform(put("/api/dashboard/stores/" + storeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("at least one product")));

        // 3) Add a product.
        String productBody = """
                {"storeId":"%s","nameEn":"Widget","slug":"widget","price":10.00,"sortOrder":0}
                """.formatted(storeId);
        String productJson = mockMvc.perform(post("/api/dashboard/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productJson, "$.data.id");

        // 4) Publish now succeeds.
        mockMvc.perform(put("/api/dashboard/stores/" + storeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // The public storefront endpoint should now serve this store.
        mockMvc.perform(get("/api/public/stores/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 5) Delete the only product — store must be demoted back to DRAFT automatically.
        mockMvc.perform(delete("/api/dashboard/products/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/dashboard/stores/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"));

        // ...and the public storefront must stop serving it.
        mockMvc.perform(get("/api/public/stores/" + slug))
                .andExpect(status().isNotFound());
    }
}
