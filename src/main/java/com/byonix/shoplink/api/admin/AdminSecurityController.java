package com.byonix.shoplink.api.admin;

import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.domain.entity.IpBlocklistEntry;
import com.byonix.shoplink.domain.entity.LoginAttempt;
import com.byonix.shoplink.domain.entity.SecurityEvent;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.security.AdminSecurityService;
import com.byonix.shoplink.service.security.SecurityMapperService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/security")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("@authz.isAdmin()")
public class AdminSecurityController {
    private final AdminSecurityService adminSecurityService;
    private final CurrentUserService currentUser;
    private final SecurityMapperService securityMapper;

    @GetMapping("/login-attempts")
    @PreAuthorize("@authz.canViewSecurityMonitoring()")
    public ApiResponse<Page<SecurityDtos.LoginAttemptResponse>> loginAttempts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(adminSecurityService.loginAttempts(PageRequest.of(page, size)).map(securityMapper::attempt));
    }

    @GetMapping("/security-events")
    @PreAuthorize("@authz.canViewSecurityMonitoring()")
    public ApiResponse<Page<SecurityDtos.SecurityEventResponse>> securityEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(adminSecurityService.securityEvents(PageRequest.of(page, size)).map(securityMapper::event));
    }

    @GetMapping("/suspicious-activity")
    @PreAuthorize("@authz.canViewSecurityMonitoring()")
    public ApiResponse<Page<SecurityDtos.LoginAttemptResponse>> suspicious(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        adminSecurityService.auditViewSuspicious(currentUser.user(), ctx.ipAddress());
        return ApiResponse.ok(adminSecurityService.suspiciousActivity(PageRequest.of(page, size)).map(securityMapper::attempt));
    }

    @GetMapping("/locked-users")
    @PreAuthorize("@authz.canViewSecurityMonitoring()")
    public ApiResponse<Page<SecurityDtos.LockedUserResponse>> lockedUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(adminSecurityService.lockedUsers(PageRequest.of(page, size)).map(securityMapper::lockedUser));
    }

    @PostMapping("/users/{userId}/unlock")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> unlock(@PathVariable UUID userId, HttpServletRequest http) {
        adminSecurityService.unlockUser(currentUser.user(), userId, ClientRequestContext.from(http).ipAddress());
        return ApiResponse.ok(null);
    }

    @PostMapping("/users/{userId}/force-password-reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> forceReset(@PathVariable UUID userId, HttpServletRequest http) {
        adminSecurityService.forcePasswordReset(currentUser.user(), userId, ClientRequestContext.from(http).ipAddress());
        return ApiResponse.ok(null);
    }

    @PostMapping("/block-ip")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<SecurityDtos.BlockIpRequest> blockIp(@Valid @RequestBody SecurityDtos.BlockIpRequest request,
                                                            HttpServletRequest http) {
        IpBlocklistEntry entry = adminSecurityService.blockIp(currentUser.user(), request,
                ClientRequestContext.from(http).ipAddress());
        return ApiResponse.ok(new SecurityDtos.BlockIpRequest(
                entry.getIpAddress(), entry.getReason(), entry.getBlockedUntil(), entry.isPermanent()));
    }

    @PostMapping("/unblock-ip")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> unblockIp(@RequestParam String ip, HttpServletRequest http) {
        adminSecurityService.unblockIp(currentUser.user(), ip, ClientRequestContext.from(http).ipAddress());
        return ApiResponse.ok(null);
    }
}
