package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.PosStaffService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** POS-14: a merchant's own POS PIN (set online, used offline at the till). */
@RestController
@RequestMapping("/api/dashboard/pos-pin")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardPosPinController {
    private final PosStaffService staffService;

    @GetMapping
    public ApiResponse<PosDtos.PinStatus> mine() {
        return ApiResponse.ok(staffService.myPin());
    }

    @PutMapping
    public ApiResponse<PosDtos.PinStatus> setMine(@Valid @RequestBody PosDtos.SetPinRequest request) {
        return ApiResponse.ok(staffService.setMyPin(request));
    }

    /** Removes a PIN: your own, or (owner) one of your staff's. */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> clear(@PathVariable UUID userId) {
        staffService.clearStaffPin(userId);
        return ApiResponse.ok(null);
    }
}
