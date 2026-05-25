package com.byonix.shoplink.security;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationExpressionsTest {
    private final AuthorizationExpressions authz = new AuthorizationExpressions();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void merchantCannotManageSecurity() {
        setRole(Role.MERCHANT_OWNER);
        assertFalse(authz.isAdmin());
        assertFalse(authz.canManageSecurity());
    }

    @Test
    void supportAdminCanViewButNotManage() {
        setRole(Role.SUPPORT_ADMIN);
        assertTrue(authz.isAdmin());
        assertTrue(authz.canViewSecurityMonitoring());
        assertFalse(authz.canManageSecurity());
    }

    @Test
    void superAdminCanManageSecurity() {
        setRole(Role.SUPER_ADMIN);
        assertTrue(authz.canManageSecurity());
    }

    private void setRole(Role role) {
        User user = new User();
        user.setRole(role);
        user.setActive(true);
        AppUserDetails principal = new AppUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
