package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT, HTTP validation, controller and persistence. V33 is not H2-compatible;
 * use the same isolated entity schema as M1-07 without changing production migrations. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:customer_history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class CustomerProfileTest extends ApiIT {
    private static final String PROFILE = "/api/public/customers/me";
    private User a, b;
    private String tokenA, tokenB;

    @BeforeEach
    void customers() {
        a = saveUser(Role.CUSTOMER, "Profile A");
        a.setPhone("+962790000001");
        a.setEmailVerifiedAt(Instant.parse("2025-01-01T00:00:00Z"));
        a.setPasswordChangedAt(Instant.parse("2025-01-01T00:00:00Z"));
        a.setTokenVersion(7);
        b = saveUser(Role.CUSTOMER, "Profile B");
        b.setPhone("+962790000002");
        tokenA = tokenFor(a); tokenB = tokenFor(b);
    }

    @Test
    void getReturnsOnlyOwnNarrowProfileEvenWithInjectedQueryIdentity() throws Exception {
        var response = send(GET, PROFILE + "?customerId=" + b.getId() + "&userId=" + b.getId()
                + "&email=" + b.getEmail() + "&phone=" + b.getPhone(), tokenA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(a.getId().toString()))
                .andExpect(jsonPath("$.data.fullName").value(a.getFullName()))
                .andExpect(jsonPath("$.data.email").value(a.getEmail()))
                .andExpect(jsonPath("$.data.phone").value(a.getPhone()));
        Map<String, Object> profile = read(response, "$.data");
        assertEquals(Set.of("id", "fullName", "email", "phone"), profile.keySet());
    }

    @Test
    void trimmedNamePersistsAndSubsequentGetReadsIt() throws Exception {
        var response = send(PUT, PROFILE, tokenA, "{\"fullName\":\"  New Customer Name  \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("New Customer Name"));
        Map<String, Object> profile = read(response, "$.data");
        assertEquals(Set.of("id", "fullName", "email", "phone"), profile.keySet());
        entityManager.flush(); entityManager.clear();
        assertEquals("New Customer Name", userRepository.findById(a.getId()).orElseThrow().getFullName());
        send(GET, PROFILE, tokenA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("New Customer Name"));
        send(GET, PROFILE, tokenB, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Profile B"));
    }

    @Test
    void anonymousAndEveryNonCustomerRoleAreRejectedForReadAndWrite() throws Exception {
        send(GET, PROFILE, null, null).andExpect(status().isUnauthorized());
        send(PUT, PROFILE, null, "{\"fullName\":\"Changed\"}").andExpect(status().isUnauthorized());
        for (Role role : Role.values()) {
            if (role == Role.CUSTOMER) continue;
            User other = saveUser(role, "Non customer");
            String token = tokenFor(other);
            send(GET, PROFILE, token, null).andExpect(status().isForbidden());
            send(PUT, PROFILE, token, "{\"fullName\":\"Changed\"}").andExpect(status().isForbidden());
            assertEquals("Non customer", userRepository.findById(other.getId()).orElseThrow().getFullName());
        }
    }

    @Test
    void injectedIdentityAndSecurityFieldsCannotChangeAnythingExceptName() throws Exception {
        entityManager.flush(); entityManager.clear();
        Map<String, Object> original = protectedFields(userRepository.findById(a.getId()).orElseThrow());
        Map<String, Object> foreign = protectedFields(userRepository.findById(b.getId()).orElseThrow());
        send(PUT, PROFILE + "?customerId=" + b.getId() + "&userId=" + b.getId(), tokenA,
                """
                {"fullName":"Allowed","id":"%s","customerId":"%s","userId":"%s",
                 "email":"attacker@example.com","phone":"999","role":"SUPER_ADMIN","roles":["SUPER_ADMIN"],
                 "authorities":["ROLE_SUPER_ADMIN"],"password":"123","passwordHash":"injected",
                 "active":false,"isActive":false,"tokenVersion":999,"accessToken":"injected","refreshToken":"injected",
                 "emailVerifiedAt":"2030-01-01T00:00:00Z","phoneVerifiedAt":"2030-01-01T00:00:00Z",
                 "failedLoginCount":999,"lockedUntil":"2030-01-01T00:00:00Z","adminUnlockRequired":true,
                 "forcePasswordReset":true,"mfaEnabled":true,"mfaSecret":"injected","suspiciousActivityFlag":true,
                 "passwordChangedAt":"2030-01-01T00:00:00Z","googleSub":"injected","lastLoginIp":"injected",
                 "lastLoginUserAgent":"injected","lastFailedLoginAt":"2030-01-01T00:00:00Z",
                 "lastSuccessfulLoginAt":"2030-01-01T00:00:00Z"}
                """.formatted(b.getId(), b.getId(), b.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(a.getId().toString()));
        entityManager.flush(); entityManager.clear();
        User updated = userRepository.findById(a.getId()).orElseThrow();
        assertEquals("Allowed", updated.getFullName());
        assertEquals(original, protectedFields(updated));
        User unchanged = userRepository.findById(b.getId()).orElseThrow();
        assertEquals("Profile B", unchanged.getFullName());
        assertEquals(foreign, protectedFields(unchanged));
        send(GET, PROFILE, tokenA, null).andExpect(status().isOk());
    }

    @Test
    void arbitraryCustomerPathsCannotReadOrModifyAnotherCustomer() throws Exception {
        for (String path : new String[]{"/api/public/customers/" + b.getId(), PROFILE + "/" + b.getId()}) {
            send(GET, path, tokenA, null).andExpect(status().isNotFound());
            send(PUT, path, tokenA, "{\"fullName\":\"Hacked\"}").andExpect(status().isNotFound());
        }
        send(GET, PROFILE, tokenB, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Profile B"));
    }

    @Test
    void invalidNamesAre400AndDoNotPersist() throws Exception {
        for (String body : new String[]{"{}", "{\"fullName\":null}", "{\"fullName\":\"\"}",
                "{\"fullName\":\"   \\t\\n\"}", "{\"fullName\":\"" + "x".repeat(161) + "\"}",
                "{\"fullName\":{}}", "{broken"}) {
            send(PUT, PROFILE, tokenA, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        }
        send(GET, PROFILE, tokenA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Profile A"));
        send(PUT, PROFILE, tokenA, "{\"fullName\":\"" + "x".repeat(160) + "\"}")
                .andExpect(status().isOk());
        send(PUT, PROFILE, tokenA, "{\"fullName\":\"  نور O’Connor-Smith  \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("نور O’Connor-Smith"));
    }

    @Test
    void missingOptionalContactFieldsStillReturnASafeProfile() throws Exception {
        User phoneOnly = userRepository.findById(a.getId()).orElseThrow();
        phoneOnly.setEmail(null);
        send(GET, PROFILE, tokenA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").doesNotExist()).andExpect(jsonPath("$.data.phone").value(a.getPhone()));
    }

    // Compare every persisted User field except the allowlisted name. Audit timestamps belong
    // to BaseAuditable, so a normal updatedAt change is intentionally outside this comparison.
    private Map<String, Object> protectedFields(User user) throws IllegalAccessException {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Field field : User.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.getName().equals("fullName")) continue;
            field.setAccessible(true);
            fields.put(field.getName(), field.get(user));
        }
        return fields;
    }
}
