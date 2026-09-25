package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.StoreCreationIdempotencyKeyRepository;
import com.byonix.shoplink.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreAcceptingOrdersTest {
    @Mock StoreRepository storeRepository;
    @Mock StoreCreationIdempotencyKeyRepository idempotencyRepository;
    @Mock ProductRepository productRepository;
    @Mock CurrentUserService currentUser;
    @Mock MapperService mapper;
    @InjectMocks StoreService storeService;

    @Test
    void storeDefaultsToAcceptingOrdersAndOwnerCanPauseAndResume() {
        Store store = store();
        User owner = new User();
        owner.setId(UUID.randomUUID());
        store.setOwner(owner);
        when(storeRepository.findById(store.getId())).thenReturn(java.util.Optional.of(store));
        when(currentUser.isSuperAdmin()).thenReturn(false);
        when(currentUser.user()).thenReturn(owner);
        when(mapper.store(store)).thenReturn(null);

        assertTrue(store.isAcceptingOrders());
        storeService.updateAcceptingOrders(store.getId(), false);
        assertFalse(store.isAcceptingOrders());
        storeService.updateAcceptingOrders(store.getId(), true);
        assertTrue(store.isAcceptingOrders());
        verify(storeRepository, times(2)).findById(store.getId());
    }

    @Test
    void ownershipCheckPreventsCrossTenantModification() {
        Store store = store();
        Store otherOwnerStore = store();
        User owner = new User();
        owner.setId(UUID.randomUUID());
        User otherOwner = new User();
        otherOwner.setId(UUID.randomUUID());
        store.setOwner(owner);
        otherOwnerStore.setOwner(otherOwner);
        when(storeRepository.findById(otherOwnerStore.getId())).thenReturn(java.util.Optional.of(otherOwnerStore));
        when(currentUser.isSuperAdmin()).thenReturn(false);
        when(currentUser.user()).thenReturn(owner);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> storeService.updateAcceptingOrders(otherOwnerStore.getId(), false));
        assertTrue(otherOwnerStore.isAcceptingOrders());
    }

    private static Store store() {
        Store store = new Store();
        store.setId(UUID.randomUUID());
        return store;
    }
}
