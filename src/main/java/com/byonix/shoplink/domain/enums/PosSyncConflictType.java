package com.byonix.shoplink.domain.enums;

/**
 * Something an uploaded offline POS sale disagreed with on the server. The sale itself is always
 * kept (the customer already paid and left with the goods); these rows are what the merchant reviews.
 */
public enum PosSyncConflictType {
    /** Two devices (or a device and the website) sold more units than the central count held. */
    OVERSOLD,
    /** The product was deleted after the device's catalog was synced; no stock could be moved. */
    PRODUCT_DELETED,
    /** The product was switched off after the sale; stock was still moved. */
    PRODUCT_UNAVAILABLE,
    VARIANT_DELETED,
    VARIANT_UNAVAILABLE,
    /** The product gained or lost variants, so the sold line no longer maps onto a stock count. */
    PRODUCT_CHANGED,
    /** The price changed after the sale; the sale keeps the price the cashier charged. */
    PRICE_CHANGED
}
