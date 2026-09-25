package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.StoreStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class StoreDtos {
    private StoreDtos() {}

    public record StoreRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase letters, numbers, and hyphens") @Size(max = 120) String slug,
            @Size(max = 1200) String description,
            @Pattern(regexp = "^$|^https?://.{3,480}$", message = "Invalid logo URL") String logoUrl,
            @Pattern(regexp = "^$|^https?://.{3,480}$", message = "Invalid cover image URL") String coverImageUrl,
            @Pattern(regexp = "^$|^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone,
            @Pattern(regexp = "^$|^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid WhatsApp number") String whatsappNumber,
            @Email @Size(max = 255) String email,
            @Size(max = 500) String address,
            @Size(max = 120) String city,
            @Size(max = 120) String country,
            BigDecimal latitude,
            BigDecimal longitude,
            @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "Invalid primary color") String primaryColor,
            @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "Invalid secondary color") String secondaryColor,
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String categorySlug,
            @Pattern(regexp = "^$|^[a-z0-9]+(?:-[a-z0-9]+)*$") String subCategorySlug,
            @Pattern(regexp = "^$|^[a-z0-9]+(?:-[a-z0-9]+)*$") String templateKey,
            StoreStatus status,
            @PositiveOrZero BigDecimal freeDeliveryThreshold,
            @Size(max = 60) String defaultEstimatedTime,
            Boolean pickupAvailable,
            String currency,
            String timezone,
            String locale) {}

    public record StoreResponse(UUID id, UUID ownerId, String name, String slug, String description, String logoUrl,
                                String coverImageUrl, String phone, String whatsappNumber, String email, String address,
                                String city, String country, BigDecimal latitude, BigDecimal longitude,
                                String primaryColor, String secondaryColor, String categorySlug, String subCategorySlug,
                                String templateKey, StoreStatus status, Instant createdAt, Instant updatedAt,
                                BigDecimal freeDeliveryThreshold, String defaultEstimatedTime, boolean pickupAvailable,
                                String currency, String timezone, String locale, boolean acceptingOrders) {}

    public record AcceptingOrdersRequest(boolean acceptingOrders) {}
}
