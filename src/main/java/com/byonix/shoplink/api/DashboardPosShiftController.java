package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.PosShiftService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * POS-24: the store's till shifts. Viewing needs the ORDERS grid (like returns and conflicts);
 * force-closing needs POS manager rights. Store access is enforced in the service.
 */
@RestController
@RequestMapping("/api/dashboard/pos-shifts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardPosShiftController {
    private final PosShiftService shiftService;

    @GetMapping
    public ApiResponse<List<PosDtos.ShiftSummary>> list(@RequestParam UUID storeId,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) UUID cashierId,
                                                        @RequestParam(required = false) UUID deviceId,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(shiftService.list(storeId, status, cashierId, deviceId, from, to));
    }

    @GetMapping("/{id}")
    public ApiResponse<PosDtos.ShiftDetail> detail(@PathVariable UUID id) {
        return ApiResponse.ok(shiftService.detail(id));
    }

    @PostMapping("/{id}/force-close")
    public ApiResponse<PosDtos.ShiftDetail> forceClose(@PathVariable UUID id, @Valid @RequestBody PosDtos.ForceCloseShiftRequest request) {
        return ApiResponse.ok(shiftService.forceClose(id, request));
    }
}
