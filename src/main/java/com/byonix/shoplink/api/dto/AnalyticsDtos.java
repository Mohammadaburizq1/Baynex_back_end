package com.byonix.shoplink.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class AnalyticsDtos {
    private AnalyticsDtos() {}

    public record DailyStoreSalesRow(LocalDate saleDate, UUID storeId, String storeName, BigDecimal totalRevenue, long orderCount) {}
}
