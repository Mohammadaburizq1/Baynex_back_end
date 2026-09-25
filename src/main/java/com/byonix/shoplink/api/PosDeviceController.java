package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.PosDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Store owners register POS devices and hand out activation codes. Owner-only, like staff management. */
@RestController
@RequestMapping("/api/dashboard/pos-devices")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT_OWNER')")
public class PosDeviceController {
    private final PosDeviceService deviceService;

    @PostMapping
    public ApiResponse<PosDtos.IssuedActivationCode> create(@Valid @RequestBody PosDtos.CreateDeviceRequest request) {
        return ApiResponse.ok(deviceService.create(request));
    }

    @GetMapping
    public ApiResponse<List<PosDtos.DeviceResponse>> list(@RequestParam UUID storeId) {
        return ApiResponse.ok(deviceService.list(storeId));
    }

    @PostMapping("/{id}/activation-code")
    public ApiResponse<PosDtos.IssuedActivationCode> reissueCode(@PathVariable UUID id) {
        return ApiResponse.ok(deviceService.reissueCode(id));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<PosDtos.DeviceResponse> revoke(@PathVariable UUID id) {
        return ApiResponse.ok(deviceService.revoke(id));
    }
}
