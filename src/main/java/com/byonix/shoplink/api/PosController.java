package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.PosDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Called by the POS app. Everything except activation requires a device credential (see SecurityConfig). */
@RestController
@RequestMapping("/api/pos")
@RequiredArgsConstructor
public class PosController {
    private final PosDeviceService deviceService;

    @PostMapping("/activate")
    public ApiResponse<PosDtos.ActivationResponse> activate(@Valid @RequestBody PosDtos.ActivateRequest request,
                                                            HttpServletRequest http) {
        return ApiResponse.ok(deviceService.activate(request, ClientRequestContext.from(http).ipAddress()));
    }

    @GetMapping("/device")
    public ApiResponse<PosDtos.DeviceSession> device(@AuthenticationPrincipal PosDevicePrincipal device) {
        return ApiResponse.ok(deviceService.session(device));
    }

    /** No storeId parameter on purpose: the store is the authenticated device's store. */
    @GetMapping("/catalog")
    public ApiResponse<PosDtos.CatalogResponse> catalog(@AuthenticationPrincipal PosDevicePrincipal device,
                                                        @RequestParam(required = false) String knownVersion) {
        return ApiResponse.ok(deviceService.catalog(device, knownVersion));
    }
}
