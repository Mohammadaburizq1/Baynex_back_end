package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.StaffDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.StaffService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// Owner-only — inviting/removing teammates is a store-settings-level action, same boundary as
// StoreController's update/delete. A store's own MERCHANT_STAFF members can't invite more staff.
@RestController
@RequestMapping("/api/dashboard/staff")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('MERCHANT_OWNER')")
public class StaffController {
    private final StaffService staffService;

    @PostMapping("/invite")
    public ApiResponse<StaffDtos.InviteResponse> invite(@Valid @RequestBody StaffDtos.InviteStaffRequest request) {
        return ApiResponse.created(staffService.inviteStaff(request));
    }

    @GetMapping
    public ApiResponse<StaffDtos.StaffListResponse> list(@RequestParam UUID storeId) {
        return ApiResponse.ok(staffService.listStaffAndInvites(storeId));
    }

    @DeleteMapping("/invites/{id}")
    public ApiResponse<Void> revokeInvite(@PathVariable UUID id) {
        staffService.revokeInvite(id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{userId}/deactivate")
    public ApiResponse<Void> deactivate(@PathVariable UUID userId) {
        staffService.deactivateStaff(userId);
        return ApiResponse.ok(null);
    }
}
