package com.byonix.shoplink.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

public final class DeliveryDtos {
    private DeliveryDtos() {}

    public record DeliveryZoneRequest(
            @NotNull UUID storeId,
            @NotBlank @Size(max = 160) String name,
            List<String> areas,
            @PositiveOrZero BigDecimal minOrder,
            @PositiveOrZero BigDecimal deliveryFee,
            @Size(max = 60) String estimatedTime,
            Boolean isActive,
            Integer sortOrder) {}

    public record DeliveryZoneResponse(
            UUID id, UUID storeId, String name, List<String> areas,
            BigDecimal minOrder, BigDecimal deliveryFee, String estimatedTime,
            boolean isActive, int sortOrder) {}

    public record PublicFulfillmentResponse(
            boolean deliveryAvailable, boolean pickupAvailable,
            BigDecimal freeDeliveryThreshold, List<DeliveryZoneResponse> zones) {}
}
