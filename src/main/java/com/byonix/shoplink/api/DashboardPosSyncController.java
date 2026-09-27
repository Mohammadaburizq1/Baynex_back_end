package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.domain.enums.PosSyncConflictStatus;
import com.byonix.shoplink.service.PosSyncConflictService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Conflicts found while uploading offline POS sales. Store access and the ORDERS grid are enforced in the service. */
@RestController
@RequestMapping("/api/dashboard/pos-sync/conflicts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardPosSyncController {
    private final PosSyncConflictService conflictService;

    @GetMapping
    public ApiResponse<PosDtos.ConflictSummary> list(@RequestParam UUID storeId,
                                                     @RequestParam(required = false) PosSyncConflictStatus status) {
        return ApiResponse.ok(conflictService.list(storeId, status));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<PosDtos.ConflictResponse> resolve(@PathVariable UUID id,
                                                         @Valid @RequestBody(required = false) PosDtos.ResolveConflictRequest request) {
        return ApiResponse.ok(conflictService.resolve(id, request));
    }
}
