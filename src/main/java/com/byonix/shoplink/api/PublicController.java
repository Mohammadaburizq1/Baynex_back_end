package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.*;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {
    private final CatalogService catalogService;
    private final StoreService storeService;
    private final OrderService orderService;
    private final RateLimitService rateLimitService;
    private final StaffService staffService;
    private final OfferService offerService;
    private final AppointmentService appointmentService;
    private final StoreThemeContentService themeContentService;
    private final BusinessHoursService businessHoursService;
    private final DeliveryZoneService deliveryZoneService;

    @GetMapping("/categories/business")
    public ApiResponse<List<CategoryDtos.CategoryResponse>> businessCategories() {
        return ApiResponse.ok(catalogService.businessCategories());
    }

    @GetMapping("/categories/business/{slug}/subcategories")
    public ApiResponse<List<CategoryDtos.CategoryResponse>> subcategories(@PathVariable String slug) {
        return ApiResponse.ok(catalogService.businessSubcategories(slug));
    }

    @GetMapping("/stores/{slug}")
    public ApiResponse<StoreDtos.StoreResponse> store(@PathVariable String slug) {
        return ApiResponse.ok(storeService.publicStoreResponse(slug));
    }

    @GetMapping("/stores/{slug}/business-hours")
    public ApiResponse<BusinessHoursDtos.Response> businessHours(@PathVariable String slug) {
        return ApiResponse.ok(businessHoursService.publicHours(slug));
    }

    @GetMapping("/stores/{slug}/fulfillment")
    public ApiResponse<DeliveryDtos.PublicFulfillmentResponse> fulfillment(@PathVariable String slug) {
        return ApiResponse.ok(deliveryZoneService.publicFulfillment(slug));
    }

    @GetMapping("/stores/{slug}/homepage")
    public ApiResponse<HomepageResponse> homepage(@PathVariable String slug) {
        Store store = storeService.publicStore(slug);
        var storeResponse = storeService.publicStoreResponse(slug);
        var featured = catalogService.publicFeaturedProducts(slug);
        var categories = catalogService.publicStoreCategories(slug);
        HomepageResponse response = new HomepageResponse(
                storeResponse,
                storeResponse.templateKey(),
                List.of(Map.of("label", "Menu", "href", "#menu"), Map.of("label", "Contact", "href", "#contact")),
                Map.of("title", store.getName(), "subtitle", store.getDescription() == null ? "" : store.getDescription()),
                featured,
                categories,
                featured,
                Map.of("status", "available"),
                Map.of("phone", store.getPhone() == null ? "" : store.getPhone(), "address", store.getAddress() == null ? "" : store.getAddress()));
        return ApiResponse.ok(response);
    }

    @GetMapping("/stores/{slug}/products")
    public ApiResponse<List<ProductDtos.ProductResponse>> products(@PathVariable String slug) {
        return ApiResponse.ok(catalogService.publicProducts(slug));
    }

    @GetMapping("/stores/{slug}/categories")
    public ApiResponse<List<CategoryDtos.CategoryResponse>> storeCategories(@PathVariable String slug) {
        return ApiResponse.ok(catalogService.publicStoreCategories(slug));
    }

    @GetMapping("/stores/{slug}/products/{productSlug}")
    public ApiResponse<ProductDtos.ProductResponse> product(@PathVariable String slug, @PathVariable String productSlug) {
        return ApiResponse.ok(catalogService.publicProduct(slug, productSlug));
    }

    @PostMapping("/stores/{slug}/orders")
    public ApiResponse<OrderDtos.OrderResponse> createOrder(@PathVariable String slug, @Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        return ApiResponse.created(orderService.createPublicOrder(slug, request));
    }

    @PostMapping("/stores/{slug}/orders/lookup")
    public ApiResponse<OrderDtos.OrderResponse> lookupOrder(@PathVariable String slug,
                                                            @Valid @RequestBody OrderDtos.OrderLookupRequest request,
                                                            HttpServletRequest http) {
        rateLimitService.checkPublicOrderLookupByIp(ClientRequestContext.from(http).ipAddress());
        return ApiResponse.ok(orderService.lookupPublicOrder(slug, request));
    }

    @GetMapping("/templates/category/{categorySlug}")
    public ApiResponse<List<TemplateResponse>> templates(@PathVariable String categorySlug) {
        return ApiResponse.ok(catalogService.templates(categorySlug));
    }

    @PostMapping("/stores/{slug}/offers/validate")
    public ApiResponse<OfferDtos.DiscountValidationResponse> validateOffer(@PathVariable String slug,
                                                                            @Valid @RequestBody OfferDtos.ValidateOfferRequest request) {
        return ApiResponse.ok(offerService.validatePublic(slug, request));
    }

    @GetMapping("/stores/{slug}/appointment-slots")
    public ApiResponse<List<AppointmentDtos.SlotResponse>> upcomingSlots(@PathVariable String slug) {
        return ApiResponse.ok(appointmentService.publicUpcomingSlots(slug));
    }

    @PostMapping("/stores/{slug}/appointments")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<AppointmentDtos.AppointmentResponse> bookAppointment(
            @PathVariable String slug, @Valid @RequestBody AppointmentDtos.CreateAppointmentRequest request) {
        return ApiResponse.created(appointmentService.createPublicAppointment(slug, request));
    }

    @GetMapping("/stores/{slug}/theme-content")
    public ApiResponse<JsonNode> themeContent(@PathVariable String slug) {
        return ApiResponse.ok(themeContentService.getPublished(slug));
    }

    @PostMapping("/staff/accept-invite")
    public ApiResponse<AuthDtos.AuthResponse> acceptStaffInvite(@Valid @RequestBody StaffDtos.AcceptInviteRequest request,
                                                                 HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.created(staffService.acceptInvite(request, http, response));
    }
}
