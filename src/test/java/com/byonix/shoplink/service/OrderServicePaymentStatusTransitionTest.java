package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// validatePaymentTransition touches no injected dependency, so OrderService is constructed
// directly with every collaborator null, same approach as OrderServiceStatusTransitionTest.
class OrderServicePaymentStatusTransitionTest {
    private final OrderService orderService = new OrderService(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void correctingBetweenUnpaidPendingFailedIsAllowedFreely() {
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.UNPAID, PaymentStatus.PENDING));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PENDING, PaymentStatus.FAILED));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.FAILED, PaymentStatus.UNPAID));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PENDING, PaymentStatus.UNPAID));
    }

    @Test
    void markingPaidIsAllowedFromUnpaidPendingOrFailed() {
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.UNPAID, PaymentStatus.PAID));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PENDING, PaymentStatus.PAID));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.FAILED, PaymentStatus.PAID));
    }

    @Test
    void refundingIsAllowedOnlyFromPaidOrPartiallyRefunded() {
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PAID, PaymentStatus.REFUNDED));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PAID, PaymentStatus.PARTIALLY_REFUNDED));
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));
    }

    @Test
    void refundingMoneyNeverReceivedIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validatePaymentTransition(PaymentStatus.UNPAID, PaymentStatus.REFUNDED));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validatePaymentTransition(PaymentStatus.PENDING, PaymentStatus.PARTIALLY_REFUNDED));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validatePaymentTransition(PaymentStatus.FAILED, PaymentStatus.REFUNDED));
    }

    @Test
    void changingStatusAfterFullRefundIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validatePaymentTransition(PaymentStatus.REFUNDED, PaymentStatus.PAID));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validatePaymentTransition(PaymentStatus.REFUNDED, PaymentStatus.UNPAID));
    }

    @Test
    void correctingBackFromPartiallyRefundedIsAllowed() {
        // A merchant fixing a mistaken partial refund — not a real "un-refund" of money.
        assertDoesNotThrow(() -> orderService.validatePaymentTransition(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.PAID));
    }
}
