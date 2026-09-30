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
    OVERRIDE_UNVERIFIED,
    /**
     * POS-23: a return asked for more units of a line than were still returnable (another till, or an
     * earlier return, already took them back). Only the remaining units were accepted; the excess
     * refund the till reported is recorded for the manager, never booked twice.
     */
    RETURN_QUANTITY_EXCEEDED,
    /** POS-23: a return that needed a POS manager's approval arrived without a confirmable one. Kept. */
    RETURN_APPROVAL_MISSING,
    /** POS-23: the exchange credit on a return and on its replacement sale disagree. Both are kept. */
    EXCHANGE_MISMATCH,
    /**
     * POS-24: a shift closed at the till with an expected drawer cash (the till's own figure) that
     * differs from the one the server computes from the records it has. Both are kept.
     */
    SHIFT_RECONCILIATION_MISMATCH,
    /** POS-24: a cash movement or shift close that needed a POS manager's approval arrived without one. Kept. */
    SHIFT_APPROVAL_MISSING,
    /** POS-26: two tills changed the same restaurant line (e.g. one voided it, the other edited it). Nothing was lost. */
    RESTAURANT_LINE_CONFLICT,
    /** POS-26: a table was opened or moved onto while another open order was already on it. Both kept. */
    RESTAURANT_TABLE_OCCUPIED,
    /** POS-26: a change arrived for a restaurant order that was already closed or merged. */
    RESTAURANT_ORDER_CLOSED,
    /** POS-26: more was paid than the bill (e.g. two tills took the last payment offline). */
    RESTAURANT_OVERPAID,
    /** POS-26: a till closed an order the server still sees a balance on; it stays open. */
    RESTAURANT_BALANCE_DUE,
    /** POS-26: a void, merge or waiter change that needed a POS manager arrived without a confirmable one. */
    RESTAURANT_APPROVAL_MISSING
}
