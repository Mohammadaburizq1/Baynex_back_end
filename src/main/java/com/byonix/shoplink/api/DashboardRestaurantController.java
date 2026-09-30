package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.RestaurantDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.RestaurantSetupService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * POS-26: restaurant setup in the dashboard — restaurant mode, floor areas, tables, a table's history.
 * Store access and the grid (ORDERS VIEW to read, POS EDIT to change) are enforced in the service.
 */
@RestController
@RequestMapping("/api/dashboard/restaurant")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardRestaurantController {
    private final RestaurantSetupService setupService;

    @GetMapping("/settings")
    public ApiResponse<RestaurantDtos.Settings> settings(@RequestParam UUID storeId) {
        return ApiResponse.ok(setupService.settings(storeId));
    }

    @PutMapping("/settings")
    public ApiResponse<RestaurantDtos.Settings> updateSettings(@RequestParam UUID storeId, @RequestBody RestaurantDtos.UpdateSettings request) {
        return ApiResponse.ok(setupService.updateSettings(storeId, request));
    }

    @GetMapping("/areas")
    public ApiResponse<List<RestaurantDtos.Area>> areas(@RequestParam UUID storeId) {
        return ApiResponse.ok(setupService.listAreas(storeId));
    }

    @PostMapping("/areas")
    public ApiResponse<RestaurantDtos.Area> createArea(@RequestParam UUID storeId, @Valid @RequestBody RestaurantDtos.AreaRequest request) {
        return ApiResponse.ok(setupService.createArea(storeId, request));
    }

    @PutMapping("/areas/{id}")
    public ApiResponse<RestaurantDtos.Area> updateArea(@PathVariable UUID id, @Valid @RequestBody RestaurantDtos.AreaRequest request) {
        return ApiResponse.ok(setupService.updateArea(id, request));
    }

    @GetMapping("/tables")
    public ApiResponse<List<RestaurantDtos.Table>> tables(@RequestParam UUID storeId) {
        return ApiResponse.ok(setupService.listTables(storeId));
    }

    @PostMapping("/tables")
    public ApiResponse<RestaurantDtos.Table> createTable(@RequestParam UUID storeId, @Valid @RequestBody RestaurantDtos.TableRequest request) {
        return ApiResponse.ok(setupService.createTable(storeId, request));
    }

    @PutMapping("/tables/{id}")
    public ApiResponse<RestaurantDtos.Table> updateTable(@PathVariable UUID id, @Valid @RequestBody RestaurantDtos.TableRequest request) {
        return ApiResponse.ok(setupService.updateTable(id, request));
    }

    @GetMapping("/tables/{id}/orders")
    public ApiResponse<List<RestaurantDtos.TableOrder>> tableHistory(@PathVariable UUID id) {
        return ApiResponse.ok(setupService.tableHistory(id));
    }
}
