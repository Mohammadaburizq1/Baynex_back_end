package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.StaffPermission;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.StaffPermissionRepository;
import com.byonix.shoplink.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrentUserService {
    private final StaffPermissionRepository staffPermissionRepository;

    public User user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails principal)) {
            throw new AccessDeniedException("Authentication required");
        }
        return principal.user();
    }

    /** The authenticated user, or null for an anonymous request (e.g. guest checkout). */
    public User userOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserDetails principal ? principal.user() : null;
    }

    /** Returns the authenticated customer only; anonymous and non-customer principals are guests. */
    public User customerOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails principal)) return null;
        User user = principal.user();
        return user.getRole() == Role.CUSTOMER ? user : null;
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

    // Layered on top of ensureStoreAccess, not replacing it — store membership is still required.
    // Owners and super admins are never gated by the grid; a staff member with no explicit row for
    // a section defaults to EDIT (see StaffPermission's table comment / the Offers-ticket-style
    // plan this shipped under) so this feature only ever removes access an owner explicitly dials
    // down, never silently breaks an existing staff account the day it ships.
    public void ensureSectionAccess(Store store, DashboardSection section, PermissionLevel required) {
        ensureStoreAccess(store);
        if (isSuperAdmin()) {
            return;
        }
        User u = user();
        if (store.getOwner().getId().equals(u.getId())) {
            return;
        }
        PermissionLevel actual = staffPermissionRepository.findByUser_IdAndSection(u.getId(), section)
                .map(StaffPermission::getLevel)
                .orElse(PermissionLevel.EDIT);
        if (actual.ordinal() < required.ordinal()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    // For list endpoints (dashboardProducts, dashboardOrders, dashboardZones, ...) that already
    // scope their results via StoreService.myStores() — a MERCHANT_STAFF's myStores() is always
    // exactly their own one store (see StoreService.myStores()), so a single check against that
    // store is equivalent to checking every returned row individually. A no-op for owners/super
    // admins (myStores() already returns only what they're allowed to see, and they're never
    // gated by the permission grid — see ensureSectionAccess).
    public void ensureListSectionAccess(DashboardSection section, PermissionLevel required) {
        User u = user();
        if (u.getRole() == Role.MERCHANT_STAFF && u.getStore() != null) {
            ensureSectionAccess(u.getStore(), section, required);
        }
    }

    // Self-service: lets a staff member fetch their OWN effective grid. Every section is always
    // present in the result (defaulted to EDIT) regardless of how many rows actually exist, so the
    // frontend never has to reason about a missing key.
    public Map<DashboardSection, PermissionLevel> effectivePermissions(UUID staffUserId) {
        Map<DashboardSection, PermissionLevel> result = new EnumMap<>(DashboardSection.class);
        for (DashboardSection section : DashboardSection.values()) {
            result.put(section, PermissionLevel.EDIT);
        }
        for (StaffPermission p : staffPermissionRepository.findByUser_Id(staffUserId)) {
            result.put(p.getSection(), p.getLevel());
        }
        return result;
    }
}
