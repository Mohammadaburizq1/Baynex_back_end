package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stock levels, alerts and the stock-change history. */
public final class InventoryDtos {
    private InventoryDtos() {}

    /** UNTRACKED = no count is kept; OUT = 0 left; LOW = at or under the threshold; OK otherwise. */
    public enum StockStatus { UNTRACKED, OK, LOW, OUT }

    /** SET makes the count exactly {@code quantity}; DELTA adds {@code quantity} (negative to remove). */
    public enum AdjustMode { SET, DELTA }

    /**
     * One stockable thing: a product without variants, or one variant of a product that has them.
     * lowStockThreshold is the item's own override (null = default); effectiveThreshold is what's
     * actually applied.
     */
    public record InventoryRow(UUID productId, UUID variantId, String name, String variantLabel, String sku,
                               Integer stock, Integer lowStockThreshold, int effectiveThreshold,
                               StockStatus status, boolean available) {}

    public record AlertsResponse(int lowCount, int outCount, List<InventoryRow> items) {}

    /** reason must be one of the merchant-selectable ones (restock, correction, damaged, returned). */
    public record AdjustRequest(
            @NotNull UUID productId,
            UUID variantId,
            @NotNull AdjustMode mode,
            @NotNull Integer quantity,
            @NotNull InventoryAdjustmentReason reason,
            @Size(max = 300) String note) {}

    /** createdBy is who made a manual change or cancellation; null for a customer's own order. */
    public record HistoryEntry(UUID id, UUID productId, UUID variantId, String itemName, int delta, int stockAfter,
                               InventoryAdjustmentReason reason, String reference, String note, String createdBy,
                               Instant createdAt) {}
}
