package com.byonix.shoplink.domain.enums;

/** Why a tracked stock count changed. The first four can be chosen by a merchant; the rest are written by the system. */
public enum InventoryAdjustmentReason {
    RESTOCK, CORRECTION, DAMAGED, RETURNED,
    INITIAL, ORDER_PLACED, ORDER_CANCELLED,
    /** POS-23: returned at a till and put back on sale. */
    POS_RETURN,
    /** POS-23: returned at a till as part of an exchange and put back on sale. */
    POS_EXCHANGE_RETURN;

    public boolean isManual() {
        return this == RESTOCK || this == CORRECTION || this == DAMAGED || this == RETURNED;
    }
}
