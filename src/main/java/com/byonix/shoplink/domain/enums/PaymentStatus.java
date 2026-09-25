package com.byonix.shoplink.domain.enums;

/**
 * Tracked independently of {@link PaymentMethod} (how the customer intends to pay) and of
 * {@link OrderStatus} (fulfillment) — a CASH order can be DELIVERED and still UNPAID, a CARD
 * order can sit PENDING before staff confirm it actually went through. No payment gateway is
 * wired up yet (see OrderService), so nothing here is set by a processor callback: it's bookkeeping
 * a merchant updates by hand via PUT /api/dashboard/orders/{id}/payment-status, ready to become
 * gateway-driven later without changing the shape of an order.
 */
public enum PaymentStatus {
    /** Nothing collected yet — the default for cash/WhatsApp-arranged orders. */
    UNPAID,
    /** A charge was intended at checkout (e.g. CARD) but not yet confirmed as received. */
    PENDING,
    PAID,
    /** An attempted charge did not go through. */
    FAILED,
    /** Fully refunded — terminal, see OrderService.validatePaymentTransition. */
    REFUNDED,
    PARTIALLY_REFUNDED
}
