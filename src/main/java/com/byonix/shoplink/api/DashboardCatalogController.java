package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.ImageDtos;
import com.byonix.shoplink.api.dto.ModifierDtos;
import com.byonix.shoplink.api.dto.VariantDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.ProductImageService;
import com.byonix.shoplink.service.ProductModifierService;
import com.byonix.shoplink.service.ProductVariantService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Everything that hangs off a product beyond the product row itself: options and variants (and, as
 * later catalog phases land, add-ons and images). Kept apart from DashboardController so that one
 * doesn't keep growing. Store access and the PRODUCTS permission level are enforced in the
 * services, per record, exactly as for the rest of the dashboard.
 */
@RestController
@RequestMapping("/api/dashboard/products/{productId}")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardCatalogController {
    private final ProductVariantService variantService;
    private final ProductModifierService modifierService;
    private final ProductImageService imageService;

    @GetMapping("/images")
    public ApiResponse<List<ImageDtos.ImageResponse>> images(@PathVariable UUID productId) {
        return ApiResponse.ok(imageService.get(productId));
    }

    @PutMapping("/images")
    public ApiResponse<List<ImageDtos.ImageResponse>> saveImages(
            @PathVariable UUID productId, @Valid @RequestBody ImageDtos.SaveImagesRequest request) {
        return ApiResponse.ok(imageService.save(productId, request));
    }

    @GetMapping("/modifier-groups")
    public ApiResponse<List<ModifierDtos.ModifierGroupResponse>> modifierGroups(@PathVariable UUID productId) {
        return ApiResponse.ok(modifierService.get(productId));
    }

    @PutMapping("/modifier-groups")
    public ApiResponse<List<ModifierDtos.ModifierGroupResponse>> saveModifierGroups(
            @PathVariable UUID productId, @Valid @RequestBody ModifierDtos.SaveModifierGroupsRequest request) {
        return ApiResponse.ok(modifierService.save(productId, request));
    }

    @GetMapping("/variants")
    public ApiResponse<VariantDtos.VariantsResponse> variants(@PathVariable UUID productId) {
        return ApiResponse.ok(variantService.get(productId));
    }

    @PutMapping("/variants")
    public ApiResponse<VariantDtos.VariantsResponse> saveVariants(
            @PathVariable UUID productId, @Valid @RequestBody VariantDtos.SaveVariantsRequest request) {
        return ApiResponse.ok(variantService.save(productId, request));
    }
}
