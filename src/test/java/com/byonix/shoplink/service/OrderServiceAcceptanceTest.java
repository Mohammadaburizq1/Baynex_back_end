package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.OfferRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceAcceptanceTest {
    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock InventoryLedger ledger;
    @Mock OfferRepository offerRepository;
    @Mock OfferService offerService;
    @Mock StoreService storeService;
    @Mock CurrentUserService currentUser;
    @Mock MapperService mapper;
    @Mock DailyStoreSalesSyncService dailyStoreSalesSync;
    @Mock OrderCodeGenerator orderCodeGenerator;
    @Mock BusinessHoursService businessHoursService;
    @InjectMocks OrderService orderService;

    @Test
    void directOrderRequestIsRejectedBeforePersistenceOrInventoryWhenClosed() {
        Store store = store();
        when(storeService.publicStore("demo")).thenReturn(store);
        doThrow(new OrderUnavailableException("STORE_CLOSED", "This store is currently closed."))
                .when(businessHoursService).assertCanAcceptOrder(store);

        assertThrows(OrderUnavailableException.class, () -> orderService.createPublicOrder("demo", request()));

        verifyNoInteractions(orderRepository, productRepository, variantRepository, ledger, dailyStoreSalesSync);
    }

    @Test
    void directOrderRequestIsRejectedBeforePersistenceWhenPaused() {
        Store store = store();
        when(storeService.publicStore("demo")).thenReturn(store);
        doThrow(new OrderUnavailableException("ORDERS_PAUSED", "Online ordering is temporarily paused."))
                .when(businessHoursService).assertCanAcceptOrder(store);

        assertThrows(OrderUnavailableException.class, () -> orderService.createPublicOrder("demo", request()));

        verifyNoInteractions(orderRepository, productRepository, variantRepository, ledger, dailyStoreSalesSync);
    }

    @Test
    void allowedOrderPassesTheCentralGuardBeforeSaving() {
        Store store = store();
        when(storeService.publicStore("demo")).thenReturn(store);
        when(orderCodeGenerator.generate()).thenReturn("ABC12345");
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.order(any(CustomerOrder.class))).thenReturn(null);

        orderService.createPublicOrder("demo", request());

        verify(businessHoursService).assertCanAcceptOrder(store);
        verify(orderRepository).save(any(CustomerOrder.class));
    }

    private static Store store() {
        Store store = new Store();
        store.setId(UUID.randomUUID());
        store.setCurrency("JOD");
        return store;
    }

    private static OrderDtos.CreateOrderRequest request() {
        return new OrderDtos.CreateOrderRequest("Customer", null, "+962790000000", null,
                null, null, null, null, null, List.of());
    }
}
