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
            UUID deliveryZoneId,
            @Size(max = 40) String discountCode,
            @Size(max = 1000) String notes,
            @NotEmpty List<@Valid CreateOrderItemRequest> items) {
        public CreateOrderRequest(String customerName, String customerEmail, String customerPhone, String customerAddress,
                                  DeliveryMethod deliveryMethod, PaymentMethod paymentMethod, BigDecimal deliveryFee,
                                  String discountCode, String notes, List<CreateOrderItemRequest> items) {
            this(customerName, customerEmail, customerPhone, customerAddress, deliveryMethod, paymentMethod,
                    deliveryFee, null, discountCode, notes, items);
        }
    }

    /**
     * variantId is required for a product that has variants and rejected for one that doesn't.
     * modifierOptionIds are the add-ons chosen (each must belong to this product; group min/max
     * rules apply) — null or empty when none.
     */
    public record CreateOrderItemRequest(@NotNull UUID productId, @Min(1) int quantity, UUID variantId,
                                         @Size(max = 30) List<@NotNull UUID> modifierOptionIds) {}
    public record StatusUpdateRequest(@NotNull OrderStatus status) {}
    public record PaymentStatusUpdateRequest(@NotNull PaymentStatus status) {}
    /** An add-on as it was when bought — plain text and a number, unaffected by later catalogue edits. */
    public record OrderModifierResponse(String groupName, String optionName, BigDecimal priceDelta) {}
    /**
     * variantLabel/sku/modifiers are what was sold, copied at purchase time — they stay true if the
     * variant or add-on is later edited or deleted. unitPrice already includes the add-on deltas.
     */
    public record OrderItemResponse(UUID id, UUID productId, String productNameSnapshot, BigDecimal unitPrice, int quantity,
                                    BigDecimal total, UUID variantId, String variantLabel, String sku,
                                    List<OrderModifierResponse> modifiers) {}

    @ValidOrderLookup
    @Schema(description = "Requires orderCode plus exactly one of: email or phone (not both, not email alone).")
    public record OrderLookupRequest(
            @NotBlank @Size(min = 6, max = 12) String orderCode,
            @Email @Size(max = 255) String email,
            @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone) {}

    public record TrackingResponse(String orderCode, OrderStatus status, DeliveryMethod deliveryMethod,
                                   BigDecimal total, String currency, Instant createdAt) {
        public static TrackingResponse from(OrderResponse order) {
            return new TrackingResponse(order.orderCode(), order.status(), order.deliveryMethod(),
                    order.total(), order.currency(), order.createdAt());
        }
    }

    public record OrderResponse(UUID id, UUID storeId, String orderCode, String customerName, String customerEmail,
                                String customerPhone, String customerAddress, DeliveryMethod deliveryMethod,
                                PaymentMethod paymentMethod, PaymentStatus paymentStatus, OrderStatus status,
                                BigDecimal subtotal, BigDecimal deliveryFee,
                                BigDecimal discount, String discountCode, BigDecimal total, String notes, Instant createdAt,
                                List<OrderItemResponse> items, String currency) {}
}
