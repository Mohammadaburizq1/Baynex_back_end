package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// updateStatus() itself is exercised end-to-end elsewhere (cancellation + restock); this class
// isolates just the transition rules so every case doesn't need the full cancel/restock mock set.
// validateTransition touches no injected dependency, so OrderService is constructed directly
// with every collaborator null rather than pulling in @InjectMocks for an empty mock set.
class OrderServiceStatusTransitionTest {
    private final OrderService orderService = new OrderService(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void forwardStepIsAllowed() {
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.NEW, OrderStatus.CONFIRMED));
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.CONFIRMED, OrderStatus.PREPARING));
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.PREPARING, OrderStatus.READY));
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.READY, OrderStatus.DELIVERED));
    }

    @Test
    void skippingStagesForwardIsAllowed() {
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.NEW, OrderStatus.DELIVERED));
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.NEW, OrderStatus.READY));
    }

    @Test
    void cancellingIsAllowedFromAnyNonTerminalState() {
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.NEW, OrderStatus.CANCELLED));
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.CONFIRMED, OrderStatus.CANCELLED));
        assertDoesNotThrow(() -> orderService.validateTransition(OrderStatus.READY, OrderStatus.CANCELLED));
    }

    @Test
    void movingBackwardIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validateTransition(OrderStatus.READY, OrderStatus.CONFIRMED));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validateTransition(OrderStatus.PREPARING, OrderStatus.NEW));
    }

    @Test
    void changingStatusAfterDeliveredIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validateTransition(OrderStatus.DELIVERED, OrderStatus.CANCELLED));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validateTransition(OrderStatus.DELIVERED, OrderStatus.READY));
    }

    @Test
    void unCancellingIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validateTransition(OrderStatus.CANCELLED, OrderStatus.CONFIRMED));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.validateTransition(OrderStatus.CANCELLED, OrderStatus.NEW));
    }
}
