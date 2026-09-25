package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.DeliveryZone;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.PaymentMethod;
import com.byonix.shoplink.repository.DeliveryZoneRepository;
import com.byonix.shoplink.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceFulfillmentTest {
    @Mock OrderRepository orderRepository;
    @Mock DeliveryZoneRepository deliveryZoneRepository;
    @Mock StoreService storeService;
    @Mock CurrentUserService currentUser;
    @Mock MapperService mapper;
    @Mock DailyStoreSalesSyncService dailyStoreSalesSync;
    @Mock OrderCodeGenerator orderCodeGenerator;
    @Mock BusinessHoursService businessHoursService;
    @InjectMocks OrderService orderService;

    @Test
    void pickupIsRejectedWhenDisabled() {
        Store store = store(false);
        when(storeService.publicStore("shop")).thenReturn(store);
        assertThrows(IllegalArgumentException.class, () -> orderService.createPublicOrder("shop", request(DeliveryMethod.PICKUP, null, BigDecimal.ZERO)));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void pickupAlwaysUsesZeroFee() {
        Store store = store(true);
        when(storeService.publicStore("shop")).thenReturn(store);
        when(orderCodeGenerator.generate()).thenReturn("ABC12345");
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.order(any())).thenReturn(null);

        orderService.createPublicOrder("shop", request(DeliveryMethod.PICKUP, null, new BigDecimal("99")));

        ArgumentCaptor<CustomerOrder> saved = ArgumentCaptor.forClass(CustomerOrder.class);
        verify(orderRepository).save(saved.capture());
        assertEquals(BigDecimal.ZERO, saved.getValue().getDeliveryFee());
    }

    @Test
    void deliveryUsesSameStoreZoneFeeInsteadOfClientFee() {
        Store store = store(true);
        UUID zoneId = UUID.randomUUID();
        DeliveryZone zone = new DeliveryZone();
        zone.setId(zoneId); zone.setStore(store); zone.setActive(true); zone.setDeliveryFee(new BigDecimal("5.00")); zone.setMinOrder(BigDecimal.ZERO);
        when(storeService.publicStore("shop")).thenReturn(store);
        when(deliveryZoneRepository.findByIdAndStore_Id(zoneId, store.getId())).thenReturn(Optional.of(zone));
        when(orderCodeGenerator.generate()).thenReturn("ABC12345");
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.order(any())).thenReturn(null);

        orderService.createPublicOrder("shop", request(DeliveryMethod.DELIVERY, zoneId, BigDecimal.ZERO));

        ArgumentCaptor<CustomerOrder> saved = ArgumentCaptor.forClass(CustomerOrder.class);
        verify(orderRepository).save(saved.capture());
        assertEquals(new BigDecimal("5.00"), saved.getValue().getDeliveryFee());
    }

    @Test
    void deliveryZoneLookupIsStoreScoped() {
        Store store = store(true);
        UUID zoneId = UUID.randomUUID();
        when(storeService.publicStore("shop")).thenReturn(store);
        when(deliveryZoneRepository.findByIdAndStore_Id(zoneId, store.getId())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> orderService.createPublicOrder("shop", request(DeliveryMethod.DELIVERY, zoneId, BigDecimal.ZERO)));
        verifyNoInteractions(orderRepository);
    }

    private Store store(boolean pickup) {
        Store store = new Store();
        store.setId(UUID.randomUUID()); store.setPickupAvailable(pickup); store.setCurrency("JOD");
        return store;
    }

    private OrderDtos.CreateOrderRequest request(DeliveryMethod method, UUID zoneId, BigDecimal clientFee) {
        return new OrderDtos.CreateOrderRequest("Customer", null, "+962790000000", "Address", method,
                PaymentMethod.CASH, clientFee, zoneId, null, null, List.of());
    }
}
