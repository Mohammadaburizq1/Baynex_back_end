package com.byonix.shoplink.security;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("authz")
public class AuthorizationExpressions {
    public boolean isAdmin() {
        return role().map(Role::isAdmin).orElse(false);
    }

    public boolean canViewSecurityMonitoring() {
        return role().map(Role::canViewSecurityMonitoring).orElse(false);
    }

    public boolean canManageSecurity() {
        return role().map(Role::canManageSecurity).orElse(false);
    }

    public boolean isMerchant() {
        return role().map(Role::isMerchant).orElse(false);
    }

    private java.util.Optional<Role> role() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails principal)) {
            return java.util.Optional.empty();
        }
        User user = principal.user();
        return java.util.Optional.of(user.getRole());
    }
}
