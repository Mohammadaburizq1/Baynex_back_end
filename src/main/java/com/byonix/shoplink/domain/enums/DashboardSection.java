package com.byonix.shoplink.domain.enums;

public enum DashboardSection {
    PRODUCTS, ORDERS, DELIVERY, CUSTOMERS, REPORTS, OFFERS, APPOINTMENTS, STOREFRONT,
    /** The in-store POS: VIEW = cashier, EDIT = POS manager. No row = cashier (see PosStaffService). */
    POS
}
