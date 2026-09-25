package com.byonix.shoplink.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CustomerDtos {
    private CustomerDtos() {}

    // customerId is null for a "guest" grouping (an order with no linked app_users account) —
    // there's no Customer entity, this is a derived read of customer_orders. See
    // OrderRepository.queryCustomerSummaries for exactly how rows are grouped.
    public record CustomerSummaryResponse(
            UUID customerId,
            String name,
            String phone,
            String email,
            long orderCount,
            BigDecimal totalSpent,
            Instant firstOrderAt,
            Instant lastOrderAt) {}

    public record ProfileUpdateRequest(@NotBlank @Size(max = 160) String fullName) {}

    public record ProfileResponse(UUID id, String fullName, String email, String phone) {}
}
