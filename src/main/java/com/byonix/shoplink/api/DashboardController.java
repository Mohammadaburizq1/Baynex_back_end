package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.*;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
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
    private final DeliveryZoneService deliveryZoneService;
    private final OfferService offerService;
    private final AppointmentService appointmentService;
    private final StoreThemeContentService themeContentService;
    private final BusinessHoursService businessHoursService;

    @PostMapping("/stores")
    public ApiResponse<StoreDtos.StoreResponse> createStore(
            @Valid @RequestBody StoreDtos.StoreRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader) {
        UUID idempotencyKey = null;
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            try {
                idempotencyKey = UUID.fromString(idempotencyKeyHeader.trim());
            } catch (IllegalArgumentException ignored) {
                // Malformed key — treat as if none were sent rather than failing the request.
            }
        }
        return ApiResponse.created(storeService.create(request, idempotencyKey));
    }

    @GetMapping("/stores/my")
    public ApiResponse<List<StoreDtos.StoreResponse>> myStores() {
        return ApiResponse.ok(storeService.myStores());
    }

    @PutMapping("/stores/{id}")
    public ApiResponse<StoreDtos.StoreResponse> updateStore(@PathVariable UUID id, @Valid @RequestBody StoreDtos.StoreRequest request) {
        return ApiResponse.ok(storeService.update(id, request));
    }

    @PutMapping("/stores/{id}/accepting-orders")
    public ApiResponse<StoreDtos.StoreResponse> updateAcceptingOrders(
            @PathVariable UUID id, @RequestBody StoreDtos.AcceptingOrdersRequest request) {
        return ApiResponse.ok(storeService.updateAcceptingOrders(id, request.acceptingOrders()));
    }

    @GetMapping("/stores/{id}/business-hours")
    public ApiResponse<BusinessHoursDtos.Response> businessHours(@PathVariable UUID id) {
        return ApiResponse.ok(businessHoursService.merchantHours(id));
    }

    @PutMapping("/stores/{id}/business-hours")
    public ApiResponse<BusinessHoursDtos.Response> updateBusinessHours(
            @PathVariable UUID id, @Valid @RequestBody BusinessHoursDtos.UpdateRequest request) {
        return ApiResponse.ok(businessHoursService.update(id, request));
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
    public ApiResponse<List<ProductDtos.ProductResponse>> products(@RequestParam(required = false) UUID storeId) {
        return ApiResponse.ok(catalogService.dashboardProducts(storeId));
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
    public ApiResponse<List<OrderDtos.OrderResponse>> orders(@RequestParam(required = false) UUID storeId) {
        return ApiResponse.ok(orderService.dashboardOrders(storeId));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderDtos.OrderResponse> order(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.dashboardOrder(id));
    }

    @PutMapping("/orders/{id}/status")
    public ApiResponse<OrderDtos.OrderResponse> updateStatus(@PathVariable UUID id, @Valid @RequestBody OrderDtos.StatusUpdateRequest request) {
        return ApiResponse.ok(orderService.updateStatus(id, request));
    }

    @PutMapping("/orders/{id}/payment-status")
    public ApiResponse<OrderDtos.OrderResponse> updatePaymentStatus(
            @PathVariable UUID id, @Valid @RequestBody OrderDtos.PaymentStatusUpdateRequest request) {
        return ApiResponse.ok(orderService.updatePaymentStatus(id, request));
    }

    @GetMapping("/delivery-zones")
    public ApiResponse<List<DeliveryDtos.DeliveryZoneResponse>> deliveryZones() {
        return ApiResponse.ok(deliveryZoneService.dashboardZones());
    }

    @PostMapping("/delivery-zones")
    public ApiResponse<DeliveryDtos.DeliveryZoneResponse> createDeliveryZone(@Valid @RequestBody DeliveryDtos.DeliveryZoneRequest request) {
        return ApiResponse.created(deliveryZoneService.create(request));
    }

    @PutMapping("/delivery-zones/{id}")
    public ApiResponse<DeliveryDtos.DeliveryZoneResponse> updateDeliveryZone(@PathVariable UUID id, @Valid @RequestBody DeliveryDtos.DeliveryZoneRequest request) {
        return ApiResponse.ok(deliveryZoneService.update(id, request));
    }

    @DeleteMapping("/delivery-zones/{id}")
    public ApiResponse<Void> deleteDeliveryZone(@PathVariable UUID id) {
        deliveryZoneService.delete(id);
        return ApiResponse.ok(null);
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

    @GetMapping("/customers")
    public ApiResponse<List<CustomerDtos.CustomerSummaryResponse>> customers(@RequestParam UUID storeId) {
        return ApiResponse.ok(orderService.customerSummaries(storeId));
    }

    @PostMapping("/offers")
    public ApiResponse<OfferDtos.OfferResponse> createOffer(@Valid @RequestBody OfferDtos.OfferRequest request) {
        return ApiResponse.created(offerService.createOffer(request));
    }

    @GetMapping("/offers")
    public ApiResponse<List<OfferDtos.OfferResponse>> offers(@RequestParam(required = false) UUID storeId) {
        return ApiResponse.ok(offerService.dashboardOffers(storeId));
    }

    @PutMapping("/offers/{id}")
    public ApiResponse<OfferDtos.OfferResponse> updateOffer(@PathVariable UUID id, @Valid @RequestBody OfferDtos.OfferRequest request) {
        return ApiResponse.ok(offerService.updateOffer(id, request));
    }

    @DeleteMapping("/offers/{id}")
    public ApiResponse<Void> deleteOffer(@PathVariable UUID id) {
        offerService.deleteOffer(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/appointment-slots")
    public ApiResponse<AppointmentDtos.SlotResponse> createSlot(@Valid @RequestBody AppointmentDtos.SlotRequest request) {
        return ApiResponse.created(appointmentService.createSlot(request));
    }

    @GetMapping("/appointment-slots")
    public ApiResponse<List<AppointmentDtos.SlotResponse>> appointmentSlots(@RequestParam(required = false) UUID storeId) {
        return ApiResponse.ok(appointmentService.dashboardSlots(storeId));
    }

    @DeleteMapping("/appointment-slots/{id}")
    public ApiResponse<Void> deleteSlot(@PathVariable UUID id) {
        appointmentService.deleteSlot(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/appointments")
    public ApiResponse<List<AppointmentDtos.AppointmentResponse>> appointments(@RequestParam(required = false) UUID storeId) {
        return ApiResponse.ok(appointmentService.dashboardAppointments(storeId));
    }

    @PutMapping("/appointments/{id}/status")
    public ApiResponse<AppointmentDtos.AppointmentResponse> updateAppointmentStatus(
            @PathVariable UUID id, @Valid @RequestBody AppointmentDtos.StatusUpdateRequest request) {
        return ApiResponse.ok(appointmentService.updateStatus(id, request));
    }

    @GetMapping("/analytics/top-products")
    public ApiResponse<List<AnalyticsDtos.TopProductRow>> topProducts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam UUID storeId,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return ApiResponse.ok(orderService.topProducts(from, to, storeId, limit));
    }

    // Raw CSV rather than the ApiResponse envelope; errors still come back as the usual JSON
    // envelope via GlobalExceptionHandler (no `produces` here, so they aren't turned into a 406).
    @GetMapping("/analytics/orders.csv")
    public ResponseEntity<byte[]> ordersCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam UUID storeId) {
        AnalyticsDtos.CsvFile file = orderService.ordersCsv(from, to, storeId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }

    @GetMapping("/theme-content")
    public ApiResponse<ThemeContentDtos.DashboardContentResponse> themeContent(@RequestParam UUID storeId) {
        return ApiResponse.ok(themeContentService.getDashboard(storeId));
    }

    @PutMapping("/theme-content")
    public ApiResponse<ThemeContentDtos.DashboardContentResponse> saveThemeDraft(
            @Valid @RequestBody ThemeContentDtos.SaveDraftRequest request) {
        return ApiResponse.ok(themeContentService.saveDraft(request.storeId(), request.content()));
    }

    @PostMapping("/theme-content/publish")
    public ApiResponse<ThemeContentDtos.DashboardContentResponse> publishThemeContent(
            @Valid @RequestBody ThemeContentDtos.PublishRequest request) {
        return ApiResponse.ok(themeContentService.publish(request.storeId()));
    }

    @GetMapping("/theme-content/versions")
    public ApiResponse<List<ThemeContentDtos.VersionSummary>> themeContentVersions(@RequestParam UUID storeId) {
        return ApiResponse.ok(themeContentService.listVersions(storeId));
    }

    @PostMapping("/theme-content/versions/restore")
    public ApiResponse<ThemeContentDtos.DashboardContentResponse> restoreThemeContentVersion(
            @Valid @RequestBody ThemeContentDtos.RestoreVersionRequest request) {
        return ApiResponse.ok(themeContentService.restoreVersion(request.storeId(), request.version()));
    }
}
