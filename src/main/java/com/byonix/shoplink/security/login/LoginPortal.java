package com.byonix.shoplink.security.login;

/**
 * Which login surface is used — enforces role separation (merchant / admin / customer).
 */
public enum LoginPortal {
    MERCHANT,
    ADMIN,
    CUSTOMER;

    public boolean isAdmin() {
        return this == ADMIN;
    }
}
