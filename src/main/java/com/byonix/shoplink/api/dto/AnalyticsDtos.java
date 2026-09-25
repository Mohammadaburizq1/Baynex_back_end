package com.byonix.shoplink.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class AnalyticsDtos {
    private AnalyticsDtos() {}

    public record DailyStoreSalesRow(LocalDate saleDate, UUID storeId, String storeName, BigDecimal totalRevenue, long orderCount) {}

    // productId/categoryName are null for a line item whose product was since deleted — see
    // OrderRepository.queryTopProducts.
    public record TopProductRow(UUID productId, String name, String categoryName, long unitsSold, BigDecimal revenue) {}

    /** A generated attachment; filename is built from validated dates only, never user input. */
    public record CsvFile(String filename, byte[] content) {}
}
