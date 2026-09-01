package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.Store;
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

    /**
     * True when the current user may act on the given store's day-to-day dashboard data
     * (products, orders, delivery zones, categories, analytics): they own it, they're the
     * MERCHANT_STAFF account scoped to it, or they're a super admin. Store-settings-level
     * mutations (edit/publish/delete the store record itself) intentionally do NOT use this —
     * see StoreService.ownedStore(), which stays owner-only.
     */
    public boolean canAccessStore(Store store) {
        if (isSuperAdmin()) {
            return true;
        }
        User u = user();
        if (store.getOwner().getId().equals(u.getId())) {
            return true;
        }
        return u.getRole() == Role.MERCHANT_STAFF && u.getStore() != null && u.getStore().getId().equals(store.getId());
    }

    public void ensureStoreAccess(Store store) {
        if (!canAccessStore(store)) {
            throw new AccessDeniedException("Access denied");
        }
    }
}
