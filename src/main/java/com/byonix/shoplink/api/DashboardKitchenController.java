package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.KitchenDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.KitchenService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * POS-27: kitchen stations and their routing (categories, product overrides) in the dashboard.
 * Store access and the grid (ORDERS VIEW to read, POS EDIT to change) are enforced in the service.
 */
@RestController
@RequestMapping("/api/dashboard/kitchen")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardKitchenController {
    private final KitchenService kitchenService;

    @GetMapping("/stations")
    public ApiResponse<List<KitchenDtos.Station>> stations(@RequestParam UUID storeId) {
        return ApiResponse.ok(kitchenService.listStations(storeId));
    }

    @PostMapping("/stations")
    public ApiResponse<KitchenDtos.Station> create(@RequestParam UUID storeId, @Valid @RequestBody KitchenDtos.StationRequest request) {
        return ApiResponse.ok(kitchenService.createStation(storeId, request));
    }

    @PutMapping("/stations/{id}")
    public ApiResponse<KitchenDtos.Station> update(@PathVariable UUID id, @Valid @RequestBody KitchenDtos.StationRequest request) {
        return ApiResponse.ok(kitchenService.updateStation(id, request));
    }

    @PutMapping("/stations/{id}/routes")
    public ApiResponse<KitchenDtos.Station> routes(@PathVariable UUID id, @Valid @RequestBody KitchenDtos.RoutesRequest request) {
        return ApiResponse.ok(kitchenService.updateRoutes(id, request));
    }
}
