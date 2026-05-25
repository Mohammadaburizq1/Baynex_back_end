package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.AuthService;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.CustomerAuthService;
import com.byonix.shoplink.service.MapperService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/auth")
@RequiredArgsConstructor
public class PublicAuthController {
    private final CustomerAuthService customerAuthService;
    private final AuthService authService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    @PostMapping("/register")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                       HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.created(customerAuthService.register(request, http, response));
    }

    @PostMapping("/login")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                    HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(customerAuthService.login(request, http, response));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthResponse> refresh(@RequestBody(required = false) AuthDtos.RefreshRequest request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(customerAuthService.refresh(
                request != null ? request : new AuthDtos.RefreshRequest(null), http, response));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    public ApiResponse<AuthDtos.MessageResponse> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request,
                                                        HttpServletRequest http, HttpServletResponse response) {
        customerAuthService.logout(request != null ? request : new AuthDtos.LogoutRequest(null), http, response);
        return ApiResponse.ok(new AuthDtos.MessageResponse(
                com.byonix.shoplink.security.login.SecurityMessages.GENERIC_LOGOUT_MESSAGE));
    }

    @PostMapping("/forgot-password")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthActionResponse> forgotPassword(
            @Valid @RequestBody AuthDtos.ForgotPasswordRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.forgotCustomerPassword(request.email(), http));
    }

    @PostMapping("/reset-password")
    @SecurityRequirements
    public ApiResponse<AuthDtos.MessageResponse> resetPassword(
            @Valid @RequestBody AuthDtos.ResetPasswordRequest request, HttpServletRequest http) {
        authService.resetCustomerPassword(request.token(), request.newPassword(), http);
        return ApiResponse.ok(new AuthDtos.MessageResponse("If the request was valid, your password has been updated."));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<AuthDtos.UserResponse> me() {
        return ApiResponse.ok(mapper.user(currentUser.user()));
    }
}
