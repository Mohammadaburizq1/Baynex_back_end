package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.domain.entity.SecurityEvent;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.security.AuthSecurityService;
import com.byonix.shoplink.service.security.SecurityMapperService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthSecurityController {
    private final AuthSecurityService authSecurityService;
    private final CurrentUserService currentUser;
    private final SecurityMapperService securityMapper;

    @GetMapping("/sessions")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<java.util.List<SecurityDtos.SessionResponse>> sessions() {
        return ApiResponse.ok(authSecurityService.listSessions(currentUser.user()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Void> revokeSession(@PathVariable UUID sessionId) {
        authSecurityService.revokeSession(currentUser.user(), sessionId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/logout-all")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Void> logoutAll(HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        authSecurityService.logoutAll(currentUser.user(), ctx.ipAddress(), ctx.userAgent());
        return ApiResponse.ok(null);
    }

    @GetMapping("/security/events")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Page<SecurityDtos.SecurityEventResponse>> securityEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SecurityEvent> events = authSecurityService.userEvents(currentUser.user(), PageRequest.of(page, size));
        return ApiResponse.ok(events.map(securityMapper::event));
    }

    @PostMapping("/change-password")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Void> changePassword(@Valid @RequestBody SecurityDtos.ChangePasswordRequest request,
                                            HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        authSecurityService.changePassword(currentUser.user(), request.currentPassword(), request.newPassword(),
                ctx.ipAddress(), ctx.userAgent());
        return ApiResponse.ok(null);
    }
}
