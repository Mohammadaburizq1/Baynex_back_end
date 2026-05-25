package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.*;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardController {
    private final StoreService storeService;
    private final CatalogService catalogService;
    private final OrderService orderService;

    @PostMapping("/stores")
    public ApiResponse<StoreDtos.StoreResponse> createStore(@Valid @RequestBody StoreDtos.StoreRequest request) {
        return ApiResponse.created(storeService.create(request));
    }

    @GetMapping("/stores/my")
    public ApiResponse<List<StoreDtos.StoreResponse>> myStores() {
        return ApiResponse.ok(storeService.myStores());
    }

    @PutMapping("/stores/{id}")
    public ApiResponse<StoreDtos.StoreResponse> updateStore(@PathVariable UUID id, @Valid @RequestBody StoreDtos.StoreRequest request) {
        return ApiResponse.ok(storeService.update(id, request));
    }

    @DeleteMapping("/stores/{id}")
    public ApiResponse<Void> deleteStore(@PathVariable UUID id) {
        storeService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryDtos.CategoryResponse> createCategory(@Valid @RequestBody CategoryDtos.CategoryRequest request) {
        return ApiResponse.created(catalogService.createCategory(request));
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryDtos.CategoryResponse>> categories() {
        return ApiResponse.ok(catalogService.dashboardCategories());
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<CategoryDtos.CategoryResponse> updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryDtos.CategoryRequest request) {
        return ApiResponse.ok(catalogService.updateCategory(id, request));
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID id) {
        catalogService.deleteCategory(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/products")
    public ApiResponse<ProductDtos.ProductResponse> createProduct(@Valid @RequestBody ProductDtos.ProductRequest request) {
        return ApiResponse.created(catalogService.createProduct(request));
    }

    @GetMapping("/products")
    public ApiResponse<List<ProductDtos.ProductResponse>> products() {
        return ApiResponse.ok(catalogService.dashboardProducts());
    }

    @GetMapping("/products/{id}")
    public ApiResponse<ProductDtos.ProductResponse> product(@PathVariable UUID id) {
        return ApiResponse.ok(catalogService.dashboardProduct(id));
    }

    @PutMapping("/products/{id}")
    public ApiResponse<ProductDtos.ProductResponse> updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductDtos.ProductRequest request) {
        return ApiResponse.ok(catalogService.updateProduct(id, request));
    }

    @DeleteMapping("/products/{id}")
    public ApiResponse<Void> deleteProduct(@PathVariable UUID id) {
        catalogService.deleteProduct(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderDtos.OrderResponse>> orders() {
        return ApiResponse.ok(orderService.dashboardOrders());
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderDtos.OrderResponse> order(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.dashboardOrder(id));
    }

    @PutMapping("/orders/{id}/status")
    public ApiResponse<OrderDtos.OrderResponse> updateStatus(@PathVariable UUID id, @Valid @RequestBody OrderDtos.StatusUpdateRequest request) {
        return ApiResponse.ok(orderService.updateStatus(id, request));
    }

    @GetMapping("/templates")
    public ApiResponse<List<TemplateResponse>> templates() {
        return ApiResponse.ok(catalogService.dashboardTemplates());
    }

    @GetMapping("/analytics/daily-store-sales")
    public ApiResponse<List<AnalyticsDtos.DailyStoreSalesRow>> dailyStoreSales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID storeId) {
        return ApiResponse.ok(orderService.dailyStoreSales(from, to, storeId));
    }
}
