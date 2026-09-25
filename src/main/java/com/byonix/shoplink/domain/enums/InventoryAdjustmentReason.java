package com.byonix.shoplink.domain.enums;

/** Why a tracked stock count changed. The first four can be chosen by a merchant; the rest are written by the system. */
public enum InventoryAdjustmentReason {
    RESTOCK, CORRECTION, DAMAGED, RETURNED,
    INITIAL, ORDER_PLACED, ORDER_CANCELLED;

    public boolean isManual() {
        return this == RESTOCK || this == CORRECTION || this == DAMAGED || this == RETURNED;
    }
}
