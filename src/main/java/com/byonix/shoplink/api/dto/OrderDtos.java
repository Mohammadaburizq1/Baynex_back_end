package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.api.dto.validation.ValidOrderLookup;
import com.byonix.shoplink.domain.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() {}

    public record CreateOrderRequest(
            @NotBlank @Size(max = 160) String customerName,
            @Email @Size(max = 255) String customerEmail,
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String customerPhone,
            @Size(max = 600) String customerAddress,
            @NotNull DeliveryMethod deliveryMethod,
            @NotNull PaymentMethod paymentMethod,
            @PositiveOrZero BigDecimal deliveryFee,
            @PositiveOrZero BigDecimal discount,
            @Size(max = 1000) String notes,
            @NotEmpty List<@Valid CreateOrderItemRequest> items) {}

    public record CreateOrderItemRequest(@NotNull UUID productId, @Min(1) int quantity) {}
    public record StatusUpdateRequest(@NotNull OrderStatus status) {}
    public record OrderItemResponse(UUID id, UUID productId, String productNameSnapshot, BigDecimal unitPrice, int quantity, BigDecimal total) {}

    @ValidOrderLookup
    @Schema(description = "Requires orderCode plus exactly one of: email or phone (not both, not email alone).")
    public record OrderLookupRequest(
            @NotBlank @Size(min = 6, max = 12) String orderCode,
            @Email @Size(max = 255) String email,
            @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone) {}

    public record OrderResponse(UUID id, UUID storeId, String orderCode, String customerName, String customerEmail,
                                String customerPhone, String customerAddress, DeliveryMethod deliveryMethod,
                                PaymentMethod paymentMethod, OrderStatus status, BigDecimal subtotal, BigDecimal deliveryFee,
                                BigDecimal discount, BigDecimal total, String notes, Instant createdAt,
                                List<OrderItemResponse> items) {}
}
