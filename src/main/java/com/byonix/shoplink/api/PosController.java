package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.PosDeviceService;
import com.byonix.shoplink.service.PosOrderSyncService;
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
    private final PosOrderSyncService orderSyncService;
    private final com.byonix.shoplink.service.PosCustomerService customerService;
    private final com.byonix.shoplink.service.PosStaffService staffService;
    private final com.byonix.shoplink.service.PosReturnSyncService returnSyncService;

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

    /** POS-12: this store's customers (accounts and guest contacts from its orders). */
    @GetMapping("/customers")
    public ApiResponse<PosDtos.CustomersResponse> customers(@AuthenticationPrincipal PosDevicePrincipal device,
                                                            @RequestParam(required = false) String knownVersion) {
        return ApiResponse.ok(customerService.customers(device, knownVersion));
    }

    /** POS-14: this store's POS people, their POS rights and PIN hashes (never PINs). */
    @GetMapping("/staff")
    public ApiResponse<PosDtos.StaffResponse> staff(@AuthenticationPrincipal PosDevicePrincipal device,
                                                    @RequestParam(required = false) String knownVersion) {
        return ApiResponse.ok(staffService.staff(device, knownVersion));
    }

    /**
     * Uploads one sale completed on the device (possibly offline). Idempotent on operationId: a retry
     * returns the same order. The store is the authenticated device's store, never the payload's.
     */
    @PostMapping("/orders/sync")
    public ApiResponse<PosDtos.SyncOrderResponse> syncOrder(@AuthenticationPrincipal PosDevicePrincipal device,
                                                            @Valid @RequestBody PosDtos.SyncOrderRequest request) {
        return ApiResponse.ok(orderSyncService.sync(device, request));
    }

    /**
     * POS-23: uploads one return or exchange made on the device (possibly offline). Idempotent on
     * operationId. The store is the authenticated device's store; the original sale must be one of its
     * POS orders, and every refund amount is recomputed from that sale.
     */
    @PostMapping("/returns/sync")
    public ApiResponse<PosDtos.SyncReturnResponse> syncReturn(@AuthenticationPrincipal PosDevicePrincipal device,
                                                              @Valid @RequestBody PosDtos.SyncReturnRequest request) {
        return ApiResponse.ok(returnSyncService.sync(device, request));
    }
}
