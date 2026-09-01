package com.byonix.shoplink.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Native query projection aggregating {@code customer_orders} into distinct customers for a
 * store (column aliases must match getters). Not backed by a Customer entity — there isn't one.
 */
public interface CustomerSummaryProjection {
    UUID getCustomerId();

    String getName();

    String getPhone();

    String getEmail();

    Long getOrderCount();

    BigDecimal getTotalSpent();

    Instant getFirstOrderAt();

    Instant getLastOrderAt();
}
