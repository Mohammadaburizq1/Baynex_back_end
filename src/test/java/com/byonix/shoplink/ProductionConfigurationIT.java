package com.byonix.shoplink;

import com.byonix.shoplink.support.PostgresTestDatabase;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        // Throwaway test-only value; never used by an application deployment.
        "app.jwt.secret=Y8wQ4zLm2R7nP9vK3xC6sB1dF5gH0jT8uA4eW9rS2yD7fG3hJ6kL1pZ5cV0bN8mX",
        "app.cors.allowed-origins=https://store.example.org",
        "app.media.storage-dir=${java.io.tmpdir}/shoplink-production-smoke-media",
        "app.media.public-base-url=https://media.example.org",
        "app.media.persistent-storage=true",
        "app.mail.frontend-base-url=https://store.example.org",
        "app.mail.enabled=false", "app.mail.disabled-acknowledged=true",
        "app.security.rate-limit.single-instance=true"
})
@ActiveProfiles("prod")
@ContextConfiguration(initializers = PostgresTestDatabase.class)
@WebAppConfiguration
class ProductionConfigurationIT {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @Test void healthIsMinimalAndHstsAppliesToHttpsOnly() throws Exception {
        mvc.perform(get("/actuator/health").secure(true)).andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health")).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
    @Test void documentationAndSensitiveActuatorAreNotPublic() throws Exception {
        for (String path : new String[]{"/swagger-ui.html", "/v3/api-docs", "/actuator/env", "/actuator/configprops"})
            mvc.perform(get(path)).andExpect(status().is4xxClientError());
    }
    @Test void corsUsesExactAllowList() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "https://store.example.org")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://store.example.org"));
        mvc.perform(options("/api/auth/login").header("Origin", "https://attacker.example.org")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
    @Test void logoutClearsSecureHttpOnlyCookie() throws Exception {
        mvc.perform(post("/api/auth/logout").secure(true).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"), org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"), org.hamcrest.Matchers.containsString("Max-Age=0"))));
    }
}
