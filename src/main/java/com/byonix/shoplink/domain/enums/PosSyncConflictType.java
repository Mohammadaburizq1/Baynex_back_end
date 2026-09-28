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
    PRICE_CHANGED,
    /** The sale named a customer account the server could not link to this store; kept as a contact. */
    CUSTOMER_UNLINKED,
    /** The discount was changed, switched off or deleted after the sale; the sale keeps what it gave. */
    DISCOUNT_CHANGED,
    /** The discount's usage limit was already reached when the sale arrived; the sale keeps it. */
    DISCOUNT_LIMIT_REACHED,
    /** The cashier is no longer staff of this store (or unknown); the sale keeps the name it showed. */
    STAFF_UNAVAILABLE,
    /** A manager approval on this sale could not be confirmed against the current permission grid. */
    OVERRIDE_UNVERIFIED
}
