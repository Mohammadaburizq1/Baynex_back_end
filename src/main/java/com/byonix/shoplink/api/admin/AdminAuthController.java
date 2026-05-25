package com.byonix.shoplink.api.admin;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.AdminAuthService;
import com.byonix.shoplink.service.CurrentUserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminAuthService adminAuthService;
    private final CurrentUserService currentUser;

    @PostMapping("/login")
    @SecurityRequirements
    public ApiResponse<SecurityDtos.AdminLoginResponse> login(@Valid @RequestBody SecurityDtos.AdminLoginRequest request,
                                                              HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(adminAuthService.adminLogin(request, http, response));
    }

    @PostMapping("/mfa/verify")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthResponse> verifyMfa(@Valid @RequestBody SecurityDtos.MfaVerifyRequest request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(adminAuthService.verifyMfa(request, http, response));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthResponse> refresh(@RequestBody(required = false) AuthDtos.RefreshRequest request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(adminAuthService.adminRefresh(
                request != null ? request : new AuthDtos.RefreshRequest(null), http, response));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    public ApiResponse<AuthDtos.MessageResponse> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request,
                                                        HttpServletRequest http, HttpServletResponse response) {
        adminAuthService.adminLogout(request != null ? request : new AuthDtos.LogoutRequest(null), http, response);
        return ApiResponse.ok(new AuthDtos.MessageResponse(
                com.byonix.shoplink.security.login.SecurityMessages.GENERIC_LOGOUT_MESSAGE));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("@authz.isAdmin()")
    public ApiResponse<AuthDtos.UserResponse> me() {
        return ApiResponse.ok(adminAuthService.adminMe(currentUser.user()));
    }

    @PostMapping("/forgot-password")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthActionResponse> forgotPassword(
            @Valid @RequestBody AuthDtos.ForgotPasswordRequest request, HttpServletRequest http) {
        return ApiResponse.ok(adminAuthService.forgotPassword(request.email(), http));
    }

    @PostMapping("/reset-password")
    @SecurityRequirements
    public ApiResponse<AuthDtos.MessageResponse> resetPassword(
            @Valid @RequestBody AuthDtos.ResetPasswordRequest request, HttpServletRequest http) {
        adminAuthService.resetPassword(request.token(), request.newPassword(), http);
        return ApiResponse.ok(new AuthDtos.MessageResponse("If the request was valid, your password has been updated."));
    }
}
