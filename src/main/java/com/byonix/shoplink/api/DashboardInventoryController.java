package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.InventoryDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.InventoryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Stock levels, low-stock alerts, controlled adjustments and the stock history. Store access and the PRODUCTS grid are enforced in the service. */
@RestController
@RequestMapping("/api/dashboard/inventory")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardInventoryController {
    private final InventoryService inventoryService;

    @GetMapping
    public ApiResponse<List<InventoryDtos.InventoryRow>> list(@RequestParam UUID storeId) {
        return ApiResponse.ok(inventoryService.list(storeId));
    }

    @GetMapping("/alerts")
    public ApiResponse<InventoryDtos.AlertsResponse> alerts(@RequestParam UUID storeId) {
        return ApiResponse.ok(inventoryService.alerts(storeId));
    }

    @GetMapping("/history")
    public ApiResponse<List<InventoryDtos.HistoryEntry>> history(
            @RequestParam UUID storeId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID variantId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(inventoryService.history(storeId, productId, variantId, limit));
    }

    @PostMapping("/adjust")
    public ApiResponse<InventoryDtos.InventoryRow> adjust(@Valid @RequestBody InventoryDtos.AdjustRequest request) {
        return ApiResponse.ok(inventoryService.adjust(request));
    }
}
