package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.StoreDtos;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.StoreCreationIdempotencyKey;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.StoreStatus;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.StoreCreationIdempotencyKeyRepository;
import com.byonix.shoplink.repository.StoreRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {
    private final StoreRepository storeRepository;
    private final StoreCreationIdempotencyKeyRepository idempotencyKeyRepository;
    private final ProductRepository productRepository;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    @Transactional
    public StoreDtos.StoreResponse create(StoreDtos.StoreRequest request, UUID idempotencyKey) {
        currentUser.requireMerchantOrAdmin();

        if (idempotencyKey != null) {
            var existing = idempotencyKeyRepository.findById(idempotencyKey);
            if (existing.isPresent()) {
                return mapper.store(existing.get().getStore());
            }
        }

        if (storeRepository.existsBySlug(request.slug())) {
            throw new IllegalArgumentException("Store slug already exists");
        }
        Store store = new Store();
        store.setOwner(currentUser.user());
        apply(store, request);
        // Server-enforced: every new store starts DRAFT regardless of what the request sends.
        // It only becomes publishable once it has at least one product — see update().
        store.setStatus(StoreStatus.DRAFT);
        Store saved = storeRepository.save(store);

        if (idempotencyKey != null) {
            StoreCreationIdempotencyKey key = new StoreCreationIdempotencyKey();
            key.setIdempotencyKey(idempotencyKey);
            key.setStore(saved);
            idempotencyKeyRepository.save(key);
        }

        return mapper.store(saved);
    }

    @Transactional(readOnly = true)
    public List<StoreDtos.StoreResponse> myStores() {
        User user = currentUser.user();
        List<Store> stores = currentUser.isSuperAdmin() ? storeRepository.findAll() : storeRepository.findByOwnerId(user.getId());
        return stores.stream().map(mapper::store).toList();
    }

    @Transactional
    public StoreDtos.StoreResponse update(UUID id, StoreDtos.StoreRequest request) {
        Store store = ownedStore(id);
        apply(store, request);
        // Only change status when the client sends it — partial updates must not reset it.
        if (request.status() != null) {
            if (request.status() == StoreStatus.ACTIVE && productRepository.countByStore_Id(store.getId()) == 0) {
                throw new IllegalArgumentException("Add at least one product before publishing your store");
            }
            store.setStatus(request.status());
        }
        return mapper.store(store);
    }

    // Called after a product is deleted — an ACTIVE store that just lost its last product no
    // longer meets the publish gate, so it's demoted back to DRAFT rather than left live and empty.
    @Transactional
    public void revertToDraftIfNoProducts(UUID storeId) {
        Store store = storeRepository.findById(storeId).orElse(null);
        if (store != null && store.getStatus() == StoreStatus.ACTIVE && productRepository.countByStore_Id(storeId) == 0) {
            store.setStatus(StoreStatus.DRAFT);
        }
    }

    @Transactional
    public void delete(UUID id) {
        storeRepository.delete(ownedStore(id));
    }

    public Store ownedStore(UUID id) {
        Store store = storeRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Store not found"));
        if (!currentUser.isSuperAdmin() && !store.getOwner().getId().equals(currentUser.user().getId())) {
            throw new AccessDeniedException("Access denied");
        }
        return store;
    }

    public Store publicStore(String slug) {
        return storeRepository.findBySlugAndStatus(slug, StoreStatus.ACTIVE).orElseThrow(() -> new EntityNotFoundException("Store not found"));
    }

    public StoreDtos.StoreResponse publicStoreResponse(String slug) {
        return mapper.store(publicStore(slug));
    }

    private void apply(Store s, StoreDtos.StoreRequest r) {
        s.setName(r.name().trim());
        s.setSlug(r.slug());
        s.setDescription(r.description());
        s.setLogoUrl(blank(r.logoUrl()));
        s.setCoverImageUrl(blank(r.coverImageUrl()));
        s.setPhone(blank(r.phone()));
        s.setWhatsappNumber(blank(r.whatsappNumber()));
        s.setEmail(blank(r.email()));
        s.setAddress(blank(r.address()));
        s.setCity(blank(r.city()));
        s.setCountry(blank(r.country()));
        s.setLatitude(r.latitude());
        s.setLongitude(r.longitude());
        s.setPrimaryColor(blank(r.primaryColor()));
        s.setSecondaryColor(blank(r.secondaryColor()));
        s.setCategorySlug(r.categorySlug());
        final String sub = blank(r.subCategorySlug());
        final String tk = blank(r.templateKey());
        s.setSubCategorySlug(sub);
        // Keep template_key aligned with sub_category_slug when a style is chosen.
        if (sub != null) {
            s.setTemplateKey(tk != null ? tk : sub);
        } else {
            s.setTemplateKey(tk);
        }
        // Status is handled by callers (create() forces DRAFT; update() gates ACTIVE on having
        // at least one product) — not touched here so both call sites go through that logic.
        s.setFreeDeliveryThreshold(r.freeDeliveryThreshold());
        s.setDefaultEstimatedTime(blank(r.defaultEstimatedTime()));
        if (r.pickupAvailable() != null) {
            s.setPickupAvailable(r.pickupAvailable());
        }
    }

    private String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
