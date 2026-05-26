package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.StoreDtos;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.StoreStatus;
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
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    @Transactional
    public StoreDtos.StoreResponse create(StoreDtos.StoreRequest request) {
        currentUser.requireMerchantOrAdmin();
        if (storeRepository.existsBySlug(request.slug())) {
            throw new IllegalArgumentException("Store slug already exists");
        }
        Store store = new Store();
        store.setOwner(currentUser.user());
        apply(store, request);
        // New merchant shops are live by default (public storefront). Send status=DRAFT to keep hidden.
        if (request.status() == null) {
            store.setStatus(StoreStatus.ACTIVE);
        }
        return mapper.store(storeRepository.save(store));
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
        return mapper.store(store);
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
        // Only change status when the client sends it (partial updates must not reset to DRAFT).
        if (r.status() != null) {
            s.setStatus(r.status());
        }
    }

    private String blank(String v) {
        if (v == null) return null;
        final t = v.replaceAll("[\\s\\u0000-\\u001F\\u007F]+", "").trim();
        return t.isEmpty() ? null : t;
    }
}
