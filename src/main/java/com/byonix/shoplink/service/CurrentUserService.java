package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.security.AppUserDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    public User user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails principal)) {
            throw new AccessDeniedException("Authentication required");
        }
        return principal.user();
    }

    public boolean isSuperAdmin() {
        return user().getRole() == Role.SUPER_ADMIN;
    }

    public void requireMerchantOrAdmin() {
        Role role = user().getRole();
        if (!role.isMerchant() && !role.isAdmin()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void requireMerchant() {
        if (!user().getRole().isMerchant()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void requireAdmin() {
        if (!user().getRole().isAdmin()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /** @deprecated use {@link #requireAdmin()} */
    public void requireSuperAdmin() {
        requireAdmin();
    }
}
