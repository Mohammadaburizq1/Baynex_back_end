package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class MapperService {
    public AuthDtos.UserResponse user(User u) {
        return new AuthDtos.UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(), u.isActive(), u.getTokenVersion(),
                u.getEmailVerifiedAt() != null, u.getPhoneVerifiedAt() != null);
    }

    public StoreDtos.StoreResponse store(Store s) {
        return new StoreDtos.StoreResponse(s.getId(), s.getOwner().getId(), s.getName(), s.getSlug(), s.getDescription(),
                s.getLogoUrl(), s.getCoverImageUrl(), s.getPhone(), s.getWhatsappNumber(), s.getEmail(), s.getAddress(),
                s.getCity(), s.getCountry(), s.getLatitude(), s.getLongitude(), s.getPrimaryColor(), s.getSecondaryColor(),
                s.getCategorySlug(), s.getSubCategorySlug(), effectiveTemplateKey(s), s.getStatus(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getFreeDeliveryThreshold(), s.getDefaultEstimatedTime(), s.isPickupAvailable(),
                s.getCurrency(), s.getTimezone(), s.getLocale(), s.isAcceptingOrders());
    }

    // The template the merchant chose wins. Onboarding sends both a template key (e.g. "ramen-shop")
    // and a coarse sub-category ("fast-food"); returning the sub-category here made every such
    // restaurant/café storefront render the generic default template. Legacy restaurant rows
    // (V12 set template_key = sub_category_slug) resolve exactly as before.
    public String effectiveTemplateKey(Store s) {
        if (s.getTemplateKey() != null && !s.getTemplateKey().isBlank()) {
            return s.getTemplateKey();
        }
        if ("restaurants-cafes".equals(s.getCategorySlug())) {
            if (s.getSubCategorySlug() != null && !s.getSubCategorySlug().isBlank()) {
                return s.getSubCategorySlug().trim();
            }
            return "restaurant-default";
        }
        return s.getTemplateKey();
    }

    public CategoryDtos.CategoryResponse category(Category c) {
        return new CategoryDtos.CategoryResponse(c.getId(), c.getStore() == null ? null : c.getStore().getId(),
                c.getParent() == null ? null : c.getParent().getId(), c.getNameEn(), c.getNameAr(), c.getSlug(),
                c.getDescription(), c.getIcon(), c.getImageUrl(), c.getSortOrder(), c.isActive(), c.getCategoryType());
    }

    // Product responses (options, variants, derived price/stock) are built by ProductAssembler,
    // which loads a whole page of products' relations at once instead of one query per product.

    public OrderDtos.OrderResponse order(CustomerOrder o) {
        return new OrderDtos.OrderResponse(o.getId(), o.getStore().getId(), o.getOrderCode(), o.getCustomerName(),
                o.getCustomerEmail(), o.getCustomerPhone(), o.getCustomerAddress(), o.getDeliveryMethod(),
                o.getPaymentMethod(), o.getPaymentStatus(), o.getStatus(), o.getSubtotal(), o.getDeliveryFee(), o.getDiscount(),
                o.getOffer() == null ? null : o.getOffer().getCode(), o.getTotal(),
                o.getNotes(), o.getCreatedAt(), o.getItems().stream().map(this::orderItem).toList(), o.getCurrency());
    }

    public OrderDtos.OrderItemResponse orderItem(OrderItem i) {
        return new OrderDtos.OrderItemResponse(i.getId(), i.getProduct() == null ? null : i.getProduct().getId(),
                i.getProductNameSnapshot(), i.getUnitPrice(), i.getQuantity(), i.getTotal(),
                i.getVariant() == null ? null : i.getVariant().getId(), i.getVariantLabel(), i.getSkuSnapshot(),
                i.getModifiers().stream()
                        .map(m -> new OrderDtos.OrderModifierResponse(m.getGroupName(), m.getOptionName(), m.getPriceDelta()))
                        .toList());
    }

    public OfferDtos.OfferResponse offer(Offer o) {
        return new OfferDtos.OfferResponse(o.getId(), o.getStore().getId(), o.getCode(), o.getDiscountType(),
                o.getDiscountValue(), o.getMinOrderAmount(), o.getMaxUses(), o.getTimesUsed(), o.getStartsAt(),
                o.getExpiresAt(), o.isActive());
    }

    public AppointmentDtos.SlotResponse appointmentSlot(AppointmentSlot s) {
        return new AppointmentDtos.SlotResponse(s.getId(), s.getStore().getId(), s.getStartsAt(), s.getEndsAt(),
                s.getCapacity(), s.getBookedCount(), s.isActive());
    }

    public AppointmentDtos.AppointmentResponse appointment(Appointment a) {
        return new AppointmentDtos.AppointmentResponse(a.getId(), a.getStore().getId(), a.getSlot().getId(),
                a.getSlot().getStartsAt(), a.getSlot().getEndsAt(),
                a.getProduct() == null ? null : a.getProduct().getId(),
                a.getProduct() == null ? null : a.getProduct().getNameEn(),
                a.getCustomerName(), a.getCustomerEmail(), a.getCustomerPhone(), a.getNotes(),
                a.getStatus(), a.getCreatedAt());
    }

    public DeliveryDtos.DeliveryZoneResponse deliveryZone(DeliveryZone z) {
        return new DeliveryDtos.DeliveryZoneResponse(z.getId(), z.getStore().getId(), z.getName(), splitAreas(z.getAreas()),
                z.getMinOrder(), z.getDeliveryFee(), z.getEstimatedTime(), z.isActive(), z.getSortOrder());
    }

    public static String joinAreas(List<String> areas) {
        return areas == null ? null : String.join(",", areas);
    }

    private static List<String> splitAreas(String areas) {
        if (areas == null || areas.isBlank()) return List.of();
        return Arrays.stream(areas.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
    }

    public TemplateResponse template(StoreTemplate t) {
        return new TemplateResponse(t.getId(), t.getCategorySlug(), t.getSubCategorySlug(), t.getTemplateKey(),
                t.getName(), t.getDescription(), t.getPreviewImageUrl(), t.isDefaultTemplate(), t.isActive());
    }
}
