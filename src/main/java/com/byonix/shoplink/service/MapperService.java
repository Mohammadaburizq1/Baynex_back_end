package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.domain.entity.*;
import org.springframework.stereotype.Service;

@Service
public class MapperService {
    public AuthDtos.UserResponse user(User u) {
        return new AuthDtos.UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(), u.isActive(), u.getTokenVersion(),
                u.getEmailVerifiedAt() != null);
    }

    public StoreDtos.StoreResponse store(Store s) {
        return new StoreDtos.StoreResponse(s.getId(), s.getOwner().getId(), s.getName(), s.getSlug(), s.getDescription(),
                s.getLogoUrl(), s.getCoverImageUrl(), s.getPhone(), s.getWhatsappNumber(), s.getEmail(), s.getAddress(),
                s.getCity(), s.getCountry(), s.getLatitude(), s.getLongitude(), s.getPrimaryColor(), s.getSecondaryColor(),
                s.getCategorySlug(), s.getSubCategorySlug(), effectiveTemplateKey(s), s.getStatus(), s.getCreatedAt(), s.getUpdatedAt());
    }

    public String effectiveTemplateKey(Store s) {
        if ("restaurants-cafes".equals(s.getCategorySlug()) && (s.getTemplateKey() == null || s.getTemplateKey().isBlank())) {
            return "restaurant-default";
        }
        return s.getTemplateKey();
    }

    public CategoryDtos.CategoryResponse category(Category c) {
        return new CategoryDtos.CategoryResponse(c.getId(), c.getStore() == null ? null : c.getStore().getId(),
                c.getParent() == null ? null : c.getParent().getId(), c.getNameEn(), c.getNameAr(), c.getSlug(),
                c.getDescription(), c.getIcon(), c.getImageUrl(), c.getSortOrder(), c.isActive(), c.getCategoryType());
    }

    public ProductDtos.ProductResponse product(Product p) {
        return new ProductDtos.ProductResponse(p.getId(), p.getStore().getId(), p.getCategory() == null ? null : p.getCategory().getId(),
                p.getNameEn(), p.getNameAr(), p.getSlug(), p.getDescription(), p.getPrice(), p.getSalePrice(), p.getCurrency(),
                p.getImageUrl(), p.getGalleryJson(), p.getSku(), p.getProductType(), p.isAvailable(), p.isFeatured(), p.getSortOrder());
    }

    public OrderDtos.OrderResponse order(CustomerOrder o) {
        return new OrderDtos.OrderResponse(o.getId(), o.getStore().getId(), o.getOrderCode(), o.getCustomerName(),
                o.getCustomerEmail(), o.getCustomerPhone(), o.getCustomerAddress(), o.getDeliveryMethod(),
                o.getPaymentMethod(), o.getStatus(), o.getSubtotal(), o.getDeliveryFee(), o.getDiscount(), o.getTotal(),
                o.getNotes(), o.getCreatedAt(), o.getItems().stream().map(this::orderItem).toList());
    }

    public OrderDtos.OrderItemResponse orderItem(OrderItem i) {
        return new OrderDtos.OrderItemResponse(i.getId(), i.getProduct() == null ? null : i.getProduct().getId(),
                i.getProductNameSnapshot(), i.getUnitPrice(), i.getQuantity(), i.getTotal());
    }

    public TemplateResponse template(StoreTemplate t) {
        return new TemplateResponse(t.getId(), t.getCategorySlug(), t.getSubCategorySlug(), t.getTemplateKey(),
                t.getName(), t.getDescription(), t.getPreviewImageUrl(), t.isDefaultTemplate(), t.isActive());
    }
}
