package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.OfferDtos;
import com.byonix.shoplink.domain.entity.Offer;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.DiscountType;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.OfferRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfferService {
    private final OfferRepository offerRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    @Transactional
    public OfferDtos.OfferResponse createOffer(OfferDtos.OfferRequest r) {
        Offer o = new Offer();
        apply(o, r, null);
        return mapper.offer(offerRepository.save(o));
    }

    // storeId is optional — same "scope to this store, or every store this merchant owns" pattern
    // as CatalogService.dashboardProducts / OrderService.dashboardOrders.
    public List<OfferDtos.OfferResponse> dashboardOffers(UUID storeId) {
        if (currentUser.isSuperAdmin()) {
            List<Offer> offers = storeId != null
                    ? offerRepository.findByStore_IdOrderByCreatedAtDesc(storeId)
                    : offerRepository.findAll();
            return offers.stream().map(mapper::offer).toList();
        }
        currentUser.ensureListSectionAccess(DashboardSection.OFFERS, PermissionLevel.VIEW);
        return storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> offerRepository.findByStore_IdOrderByCreatedAtDesc(s.id()).stream())
                .map(mapper::offer).toList();
    }

    @Transactional
    public OfferDtos.OfferResponse updateOffer(UUID id, OfferDtos.OfferRequest r) {
        Offer o = offerRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Offer not found"));
        currentUser.ensureSectionAccess(o.getStore(), DashboardSection.OFFERS, PermissionLevel.EDIT);
        apply(o, r, id);
        return mapper.offer(o);
    }

    @Transactional
    public void deleteOffer(UUID id) {
        Offer o = offerRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Offer not found"));
        currentUser.ensureSectionAccess(o.getStore(), DashboardSection.OFFERS, PermissionLevel.EDIT);
        offerRepository.delete(o);
    }

    // Called from OrderService during checkout. Throws IllegalArgumentException with a specific,
    // customer-facing message (bad code / expired / below minimum / exhausted) — the caller lets
    // that propagate straight to the customer via GlobalExceptionHandler's 400 mapping. No auth
    // check here: this runs both from the public "validate code" endpoint and from inside
    // createPublicOrder, both of which are public/customer-facing by design.
    public DiscountResult validateAndComputeDiscount(Store store, String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Discount code is required");
        }
        Offer offer = offerRepository.findByStore_IdAndCodeIgnoreCase(store.getId(), code.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid discount code"));
        if (!offer.isActive()) {
            throw new IllegalArgumentException("This discount code is no longer active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (offer.getStartsAt() != null && now.isBefore(offer.getStartsAt())) {
            throw new IllegalArgumentException("This discount code is not active yet");
        }
        if (offer.getExpiresAt() != null && now.isAfter(offer.getExpiresAt())) {
            throw new IllegalArgumentException("This discount code has expired");
        }
        if (offer.getMaxUses() != null && offer.getTimesUsed() >= offer.getMaxUses()) {
            throw new IllegalArgumentException("This discount code has reached its usage limit");
        }
        if (offer.getMinOrderAmount() != null && subtotal.compareTo(offer.getMinOrderAmount()) < 0) {
            throw new IllegalArgumentException("Order must be at least " + offer.getMinOrderAmount() + " to use this code");
        }
        BigDecimal amount = offer.getDiscountType() == DiscountType.PERCENTAGE
                ? subtotal.multiply(offer.getDiscountValue()).divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP)
                : offer.getDiscountValue();
        // Never discount more than the order is actually worth, regardless of what a fixed-amount
        // code was configured for.
        if (amount.compareTo(subtotal) > 0) {
            amount = subtotal;
        }
        return new DiscountResult(offer, amount);
    }

    public record DiscountResult(Offer offer, BigDecimal amount) {}

    // Storefront-facing "Apply" button — same validation as checkout itself, but without
    // redeeming a use (that only happens once an order is actually placed, in OrderService).
    public OfferDtos.DiscountValidationResponse validatePublic(String storeSlug, OfferDtos.ValidateOfferRequest r) {
        Store store = storeService.publicStore(storeSlug);
        DiscountResult result = validateAndComputeDiscount(store, r.code(), r.subtotal());
        return new OfferDtos.DiscountValidationResponse(result.offer().getId(), result.offer().getCode(), result.amount());
    }

    private void apply(Offer o, OfferDtos.OfferRequest r, UUID selfId) {
        Store store = storeService.accessibleStore(r.storeId());
        currentUser.ensureSectionAccess(store, DashboardSection.OFFERS, PermissionLevel.EDIT);
        String code = r.code().trim().toUpperCase();
        boolean duplicate = selfId == null
                ? offerRepository.existsByStore_IdAndCodeIgnoreCase(store.getId(), code)
                : offerRepository.existsByStore_IdAndCodeIgnoreCaseAndIdNot(store.getId(), code, selfId);
        if (duplicate) {
            throw new IllegalArgumentException("A discount code with this code already exists");
        }
        if (r.discountType() == DiscountType.PERCENTAGE && r.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100");
        }
        o.setStore(store);
        o.setCode(code);
        o.setDiscountType(r.discountType());
        o.setDiscountValue(r.discountValue());
        o.setMinOrderAmount(r.minOrderAmount());
        o.setMaxUses(r.maxUses());
        o.setStartsAt(r.startsAt());
        o.setExpiresAt(r.expiresAt());
        o.setActive(r.active() == null || r.active());
    }
}
