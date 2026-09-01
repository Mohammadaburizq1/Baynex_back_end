package com.byonix.shoplink.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Native query projection aggregating order_items into per-product sales totals for a store's
 * Reports page (column aliases must match getters). categoryName is null for an uncategorized
 * product, or for a snapshot item whose product was since deleted (productId is then also null).
 */
public interface TopProductProjection {
    UUID getProductId();

    String getName();

    String getCategoryName();

    Long getUnitsSold();

    BigDecimal getRevenue();
}
