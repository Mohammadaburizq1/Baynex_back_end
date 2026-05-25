package com.byonix.shoplink.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Native query projection for {@code daily_store_sales} view (column aliases must match getters).
 */
public interface DailyStoreSalesProjection {
    UUID getStoreId();

    String getStoreName();

    LocalDate getSaleDate();

    BigDecimal getTotalRevenue();

    Long getOrderCount();
}
