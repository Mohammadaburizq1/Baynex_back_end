package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceLookupTest {
    @Mock StoreService storeService;
    @Mock OrderRepository orderRepository;
    @Mock MapperService mapper;
    @Mock CurrentUserService currentUser;
    @Mock DailyStoreSalesSyncService dailyStoreSalesSync;
    @Mock OrderCodeGenerator orderCodeGenerator;
    @InjectMocks OrderService orderService;

    @Test
    void lookupFailsWithOrderCodeAndBothEmailAndPhone() {
        when(storeService.publicStore(anyString())).thenReturn(new Store());
        assertThrows(IllegalArgumentException.class, () ->
                orderService.lookupPublicOrder("slug", new OrderDtos.OrderLookupRequest("ABC12345", "a@b.com", "+962790000000")));
    }

    @Test
    void lookupSucceedsWithOrderCodeAndPhone() {
        Store store = new Store();
        store.setId(UUID.randomUUID());
        CustomerOrder order = new CustomerOrder();
        order.setOrderCode("ABC12345");
        when(storeService.publicStore(anyString())).thenReturn(store);
        when(orderRepository.findByStore_SlugAndOrderCodeIgnoreCaseAndCustomerPhone("slug", "ABC12345", "+962790000000"))
                .thenReturn(Optional.of(order));
        when(mapper.order(order)).thenReturn(new OrderDtos.OrderResponse(
                UUID.randomUUID(), store.getId(), "ABC12345", "n", null, "+962790000000", null,
                null, null, null, null, null, null, null, null, null, null, java.util.List.of()));

        orderService.lookupPublicOrder("slug", new OrderDtos.OrderLookupRequest("ABC12345", null, "+962790000000"));
    }
}
