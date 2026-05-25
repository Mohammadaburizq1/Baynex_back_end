package com.byonix.shoplink.domain.enums;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    SUPER_ADMIN,
    SUPPORT_ADMIN,
    FINANCE_ADMIN,
    READ_ONLY_ADMIN,
    MERCHANT_OWNER,
    MERCHANT_STAFF,
    CUSTOMER;

    private static final Set<Role> ADMIN_ROLES = EnumSet.of(
            SUPER_ADMIN, SUPPORT_ADMIN, FINANCE_ADMIN, READ_ONLY_ADMIN);

    private static final Set<Role> MERCHANT_ROLES = EnumSet.of(MERCHANT_OWNER, MERCHANT_STAFF);

    private static final Set<Role> SECURITY_ADMIN_ROLES = EnumSet.of(
            SUPER_ADMIN, SUPPORT_ADMIN, READ_ONLY_ADMIN);

    private static final Set<Role> SECURITY_WRITE_ADMIN_ROLES = EnumSet.of(SUPER_ADMIN);

    public boolean isAdmin() {
        return ADMIN_ROLES.contains(this);
    }

    public boolean isMerchant() {
        return MERCHANT_ROLES.contains(this);
    }

    public boolean isCustomer() {
        return this == CUSTOMER;
    }

    public boolean canViewSecurityMonitoring() {
        return SECURITY_ADMIN_ROLES.contains(this);
    }

    public boolean canManageSecurity() {
        return SECURITY_WRITE_ADMIN_ROLES.contains(this);
    }

    public boolean requiresMfaChallenge() {
        return this == SUPER_ADMIN;
    }
}
