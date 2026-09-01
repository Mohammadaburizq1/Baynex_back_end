package com.byonix.shoplink.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
}
