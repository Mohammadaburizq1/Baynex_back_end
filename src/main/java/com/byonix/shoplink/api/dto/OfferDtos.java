package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.DiscountType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class OfferDtos {
    private OfferDtos() {}

    public record OfferRequest(
            @NotNull UUID storeId,
            @NotBlank @Size(max = 40) String code,
            @NotNull DiscountType discountType,
            @NotNull @Positive BigDecimal discountValue,
            @PositiveOrZero BigDecimal minOrderAmount,
            @Positive Integer maxUses,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            Boolean active) {}

    public record OfferResponse(UUID id, UUID storeId, String code, DiscountType discountType, BigDecimal discountValue,
                                BigDecimal minOrderAmount, Integer maxUses, int timesUsed, OffsetDateTime startsAt,
                                OffsetDateTime expiresAt, boolean active) {}

    public record ValidateOfferRequest(@NotBlank @Size(max = 40) String code, @NotNull @PositiveOrZero BigDecimal subtotal) {}

    // Result of validating a code against a real order subtotal — amount is the computed discount
    // (never more than the subtotal itself), offerId identifies which offer to atomically
    // increment usage on and to link the resulting order to.
    public record DiscountValidationResponse(UUID offerId, String code, BigDecimal amount) {}
}
