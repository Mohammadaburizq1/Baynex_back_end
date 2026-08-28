package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,}$",
                    message = "Password must be at least 10 characters and include uppercase, lowercase, number, and special character") String password,
            @Pattern(regexp = "^$|^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone) {}

    /** Merchant onboarding — phone is the primary identifier; email is generated server-side. */
    public record RegisterByPhoneRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone,
            @Size(max = 160) String fullName,
            @Size(max = 160) String shopName,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,}$",
                    message = "Password must be at least 10 characters and include uppercase, lowercase, number, and special character") String password) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record LoginByPhoneRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone,
            @NotBlank String password) {}

    public record GoogleLoginRequest(@NotBlank String idToken) {}

    @Schema(description = """
            Refresh token. Required in JSON body when app.auth.refresh-token-delivery=BODY (mobile/default).
            Optional when COOKIE or COOKIE_OR_BODY is enabled — send HttpOnly cookie `shoplink_refresh` instead.""")
    public record RefreshRequest(
            @Size(min = 1, max = 512) String refreshToken) {}

    @Schema(description = """
            Refresh token to revoke. Same rules as refresh: body required for BODY mode, or HttpOnly cookie `shoplink_refresh`.""")
    public record LogoutRequest(
            @Size(min = 1, max = 512) String refreshToken) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Authentication tokens. Never includes email-verification or password-reset tokens.")
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            UserResponse user,
            Integer riskScore,
            Boolean requiresExtraVerification) {}

    public record VerifyEmailRequest(@NotBlank String token) {}
    public record ResendVerificationRequest(@NotBlank @Email @Size(max = 255) String email) {}
    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {}
    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,}$",
                    message = "Password must be at least 10 characters and include uppercase, lowercase, number, and special character") String newPassword) {}

    public record VerifyPhoneRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone,
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code) {}

    public record ResendPhoneVerificationRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone) {}

    public record ForgotPasswordPhoneRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone) {}

    public record ResetPasswordPhoneRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String phone,
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,}$",
                    message = "Password must be at least 10 characters and include uppercase, lowercase, number, and special character") String newPassword) {}

    public record MessageResponse(String message) {}

    @Schema(description = "Generic message only — no verification or reset tokens in production.")
    public record AuthActionResponse(String message) {}

    public record UserResponse(UUID id, String fullName, String email, String phone, Role role, boolean active, int tokenVersion,
                               boolean emailVerified, boolean phoneVerified) {}

    public record PhoneAvailabilityResponse(boolean available) {}
}
