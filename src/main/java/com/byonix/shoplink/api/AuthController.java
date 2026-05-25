package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.AuthService;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.MapperService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                       HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.created(authService.register(request, http, response));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                    HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(authService.login(request, http, response));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public ApiResponse<AuthDtos.AuthResponse> refresh(@RequestBody(required = false) AuthDtos.RefreshRequest request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        return ApiResponse.ok(authService.refresh(
                request != null ? request : new AuthDtos.RefreshRequest(null), http, response));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    public ApiResponse<AuthDtos.MessageResponse> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request,
                                                        HttpServletRequest http, HttpServletResponse response) {
        authService.logout(request != null ? request : new AuthDtos.LogoutRequest(null), http, response);
        return ApiResponse.ok(new AuthDtos.MessageResponse(
                com.byonix.shoplink.security.login.SecurityMessages.GENERIC_LOGOUT_MESSAGE));
    }

    @PostMapping("/verify-email")
    public ApiResponse<AuthDtos.MessageResponse> verifyEmail(@Valid @RequestBody AuthDtos.VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ApiResponse.ok(new AuthDtos.MessageResponse("If the link was valid, your email is now verified."));
    }

    @PostMapping("/verify-email/resend")
    public ApiResponse<AuthDtos.AuthActionResponse> resendVerification(
            @Valid @RequestBody AuthDtos.ResendVerificationRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.resendVerificationEmail(request.email(), http));
    }

    @PostMapping("/forgot-password")
    public ApiResponse<AuthDtos.AuthActionResponse> forgotPassword(
            @Valid @RequestBody AuthDtos.ForgotPasswordRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.forgotPassword(request.email(), http));
    }

    @PostMapping("/reset-password")
    public ApiResponse<AuthDtos.MessageResponse> resetPassword(
            @Valid @RequestBody AuthDtos.ResetPasswordRequest request, HttpServletRequest http) {
        authService.resetPassword(request.token(), request.newPassword(), http);
        return ApiResponse.ok(new AuthDtos.MessageResponse("If the request was valid, your password has been updated."));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<AuthDtos.UserResponse> me() {
        return ApiResponse.ok(mapper.user(currentUser.user()));
    }
}
