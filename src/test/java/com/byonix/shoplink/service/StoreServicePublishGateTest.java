package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.StoreDtos;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.StoreStatus;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.StoreCreationIdempotencyKeyRepository;
import com.byonix.shoplink.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the publish gate added on top of DRAFT/ACTIVE: new stores always start DRAFT
 * (regardless of what the request sends), and a store can only move to ACTIVE once it has
 * at least one product. Mirrors the lifecycle a merchant actually walks through: create ->
 * blocked publish attempt -> add a product -> publish succeeds -> deleting the last product
 * demotes the store back to DRAFT.
 */
@ExtendWith(MockitoExtension.class)
class StoreServicePublishGateTest {
    @Mock StoreRepository storeRepository;
    @Mock StoreCreationIdempotencyKeyRepository idempotencyKeyRepository;
    @Mock ProductRepository productRepository;
    @Mock CurrentUserService currentUser;
    @Mock MapperService mapper;
    @InjectMocks StoreService storeService;

    private StoreDtos.StoreRequest requestWithStatus(StoreStatus status) {
        return new StoreDtos.StoreRequest(
                "My Store", "my-store", null, null, null, null, null, null, null, null, null,
                null, null, null, null, "general-store", null, null,
                status, null, null, null, null, null, null);
    }

    private StoreDtos.StoreRequest requestWithSettings(String currency, String timezone, String locale) {
        return new StoreDtos.StoreRequest(
                "My Store", "my-store", null, null, null, null, null, null, null, null, null,
                null, null, null, null, "general-store", null, null,
                null, null, null, null, currency, timezone, locale);
    }

    @Test
    void createUsesDeterministicSettingsDefaults() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        when(currentUser.user()).thenReturn(owner);
        when(storeRepository.existsBySlug("my-store")).thenReturn(false);
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));

        storeService.create(requestWithStatus(null), null);

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        assertEquals("JOD", captor.getValue().getCurrency());
        assertEquals("UTC", captor.getValue().getTimezone());
        assertEquals("en", captor.getValue().getLocale());
    }

    @Test
    void updatePersistsValidatedStoreSettings() {
        Store store = ownedDraftStore();
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(currentUser.isSuperAdmin()).thenReturn(true);

        storeService.update(store.getId(), requestWithSettings("usd", "Asia/Amman", "AR"));

        assertEquals("USD", store.getCurrency());
        assertEquals("Asia/Amman", store.getTimezone());
        assertEquals("ar", store.getLocale());
    }

    @Test
    void invalidStoreSettingsAreRejected() {
        Store store = ownedDraftStore();
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(currentUser.isSuperAdmin()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> storeService.update(store.getId(), requestWithSettings("ZZZ", "Not/A/Timezone", "fr")));
    }

    @Test
    void createAlwaysStartsDraftEvenIfRequestAsksForActive() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        when(currentUser.user()).thenReturn(owner);
        when(storeRepository.existsBySlug("my-store")).thenReturn(false);
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));

        // Client explicitly asks for ACTIVE — server must ignore this on create.
        storeService.create(requestWithStatus(StoreStatus.ACTIVE), null);

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        assertEquals(StoreStatus.DRAFT, captor.getValue().getStatus());
    }

    @Test
    void createIgnoresNullStatusAndStillStartsDraft() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        when(currentUser.user()).thenReturn(owner);
        when(storeRepository.existsBySlug("my-store")).thenReturn(false);
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));

        storeService.create(requestWithStatus(null), null);

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        assertEquals(StoreStatus.DRAFT, captor.getValue().getStatus());
    }

    @Test
    void publishFailsWithZeroProducts() {
        Store store = ownedDraftStore();
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(currentUser.isSuperAdmin()).thenReturn(true);
        when(productRepository.countByStore_Id(store.getId())).thenReturn(0L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                storeService.update(store.getId(), requestWithStatus(StoreStatus.ACTIVE)));
        assertTrue(ex.getMessage().toLowerCase().contains("at least one product"));
        assertEquals(StoreStatus.DRAFT, store.getStatus(), "status must not change when the gate rejects the request");
    }

    @Test
    void publishSucceedsOnceStoreHasAProduct() {
        Store store = ownedDraftStore();
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(currentUser.isSuperAdmin()).thenReturn(true);
        when(productRepository.countByStore_Id(store.getId())).thenReturn(1L);

        storeService.update(store.getId(), requestWithStatus(StoreStatus.ACTIVE));

        assertEquals(StoreStatus.ACTIVE, store.getStatus());
    }

    @Test
    void updateWithoutStatusFieldLeavesStatusUnchanged() {
        Store store = ownedDraftStore();
        store.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(currentUser.isSuperAdmin()).thenReturn(true);

        storeService.update(store.getId(), requestWithStatus(null));

        assertEquals(StoreStatus.ACTIVE, store.getStatus());
        verifyNoInteractions(productRepository);
    }

    @Test
    void revertToDraftDemotesActiveStoreThatLostItsLastProduct() {
        Store store = ownedDraftStore();
        store.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(productRepository.countByStore_Id(store.getId())).thenReturn(0L);

        storeService.revertToDraftIfNoProducts(store.getId());

        assertEquals(StoreStatus.DRAFT, store.getStatus());
    }

    @Test
    void revertToDraftLeavesActiveStoreAloneWhenProductsRemain() {
        Store store = ownedDraftStore();
        store.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(store.getId())).thenReturn(Optional.of(store));
        when(productRepository.countByStore_Id(store.getId())).thenReturn(2L);

        storeService.revertToDraftIfNoProducts(store.getId());

        assertEquals(StoreStatus.ACTIVE, store.getStatus());
    }

    private Store ownedDraftStore() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        Store store = new Store();
        store.setId(UUID.randomUUID());
        store.setOwner(owner);
        store.setStatus(StoreStatus.DRAFT);
        return store;
    }
}
