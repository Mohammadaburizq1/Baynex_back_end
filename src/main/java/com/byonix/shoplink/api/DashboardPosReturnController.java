package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.PosReturnSyncService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** POS-23: returns and exchanges made at the store's tills. Store access and the ORDERS grid are enforced in the service. */
@RestController
@RequestMapping("/api/dashboard/pos-returns")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardPosReturnController {
    private final PosReturnSyncService returnService;

    @GetMapping
    public ApiResponse<List<PosDtos.ReturnSummary>> list(@RequestParam UUID storeId, @RequestParam(required = false) UUID orderId) {
        return ApiResponse.ok(returnService.list(storeId, orderId));
    }
}
