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
    private final com.byonix.shoplink.service.PosShiftService shiftService;
    private final com.byonix.shoplink.service.PosRestaurantService restaurantService;
    private final com.byonix.shoplink.service.RestaurantSetupService restaurantSetupService;
    private final com.byonix.shoplink.service.KitchenService kitchenService;

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

    /** POS-26: restaurant mode, floor areas, tables and delivery zones of the device's store. */
    @GetMapping("/restaurant/setup")
    public ApiResponse<com.byonix.shoplink.api.dto.RestaurantDtos.PosSetup> restaurantSetup(@AuthenticationPrincipal PosDevicePrincipal device) {
        return ApiResponse.ok(restaurantSetupService.posSetup(device));
    }

    /**
     * POS-26: one change to a restaurant order (open, add items, void, move, merge, pay, close…), made at
     * a till possibly offline. Idempotent on operationId; answers with the order as the server has it now.
     */
    @PostMapping("/restaurant/operations")
    public ApiResponse<com.byonix.shoplink.api.dto.RestaurantDtos.OpResponse> restaurantOperation(
            @AuthenticationPrincipal PosDevicePrincipal device, @Valid @RequestBody com.byonix.shoplink.api.dto.RestaurantDtos.OpRequest request) {
        return ApiResponse.ok(restaurantService.apply(device, request));
    }

    /** POS-26: the store's open restaurant orders and those changed since [since] (what other tills did). */
    @GetMapping("/restaurant/orders")
    public ApiResponse<com.byonix.shoplink.api.dto.RestaurantDtos.StateResponse> restaurantOrders(
            @AuthenticationPrincipal PosDevicePrincipal device, @RequestParam(required = false) java.time.Instant since) {
        return ApiResponse.ok(restaurantService.state(device, since));
    }

    /** POS-27: open kitchen tickets of the store and every ticket changed since [since]. */
    @GetMapping("/kitchen/tickets")
    public ApiResponse<com.byonix.shoplink.api.dto.KitchenDtos.TicketsResponse> kitchenTickets(
            @AuthenticationPrincipal PosDevicePrincipal device, @RequestParam(required = false) java.time.Instant since) {
        return ApiResponse.ok(kitchenService.tickets(device, since));
    }

    /** POS-27: a status change or recall made on a kitchen screen (possibly offline). Idempotent on operationId. */
    @PostMapping("/kitchen/operations")
    public ApiResponse<com.byonix.shoplink.api.dto.KitchenDtos.KitchenOpResponse> kitchenOperation(
            @AuthenticationPrincipal PosDevicePrincipal device, @Valid @RequestBody com.byonix.shoplink.api.dto.KitchenDtos.KitchenOp request) {
        return ApiResponse.ok(kitchenService.apply(device, request));
    }

    /**
     * POS-24: a shift opened at the till (possibly offline). Idempotent on operationId; the store is the
     * authenticated device's, the shift belongs to the device that opened it.
     */
    @PostMapping("/shifts/open")
    public ApiResponse<PosDtos.ShiftSyncResponse> openShift(@AuthenticationPrincipal PosDevicePrincipal device,
                                                            @Valid @RequestBody PosDtos.OpenShiftRequest request) {
        return ApiResponse.ok(shiftService.open(device, request));
    }

    /** POS-24: cash put into or taken out of the shift's drawer. Idempotent on operationId. */
    @PostMapping("/shifts/{shiftId}/cash-movements")
    public ApiResponse<PosDtos.ShiftSyncResponse> cashMovement(@AuthenticationPrincipal PosDevicePrincipal device,
                                                               @PathVariable java.util.UUID shiftId,
                                                               @Valid @RequestBody PosDtos.CashMovementRequest request) {
        return ApiResponse.ok(shiftService.cashMovement(device, shiftId, request));
    }

    /**
     * POS-24: the counted close of a shift. The server recomputes the expected cash from its own records
     * and keeps the till's figure beside it. Idempotent on operationId; a shift closes once.
     */
    @PostMapping("/shifts/{shiftId}/close")
    public ApiResponse<PosDtos.ShiftSyncResponse> closeShift(@AuthenticationPrincipal PosDevicePrincipal device,
                                                             @PathVariable java.util.UUID shiftId,
                                                             @Valid @RequestBody PosDtos.CloseShiftRequest request) {
        return ApiResponse.ok(shiftService.close(device, shiftId, request));
    }
}
