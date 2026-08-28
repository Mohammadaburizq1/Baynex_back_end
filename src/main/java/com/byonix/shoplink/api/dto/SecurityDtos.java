package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.LoginFailureReason;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class SecurityDtos {
    private SecurityDtos() {}

    public record AdminLoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = """
            Admin login step 1. When mfaRequired=true, only mfaChallengeToken is returned (no access/refresh tokens).
            Complete login via POST /api/admin/auth/mfa/verify to receive AuthResponse.""")
    public record AdminLoginResponse(
            boolean mfaRequired,
            @Schema(description = "Present only when mfaRequired=true. Short-lived JWT for MFA step.")
            String mfaChallengeToken,
            @Schema(description = "Present only when mfaRequired=false. Full session tokens.")
            AuthDtos.AuthResponse auth) {
        public static AdminLoginResponse mfaChallenge(String mfaChallengeToken) {
            return new AdminLoginResponse(true, mfaChallengeToken, null);
        }

        public static AdminLoginResponse authenticated(AuthDtos.AuthResponse auth) {
            return new AdminLoginResponse(false, null, auth);
        }
    }

    @Schema(description = "Admin MFA step 2 — returns full AuthResponse with access and refresh tokens.")
    public record MfaVerifyRequest(@NotBlank String mfaChallengeToken, @NotBlank String mfaCode) {}

    @Schema(description = """
            First-time MFA enrollment. Uses the same short-lived mfaChallengeToken returned by /login
            (proves the password was already correct). Only works while no secret is configured yet —
            once enrolled, re-running setup is rejected.""")
    public record MfaSetupRequest(@NotBlank String mfaChallengeToken) {}

    public record MfaSetupResponse(
            @Schema(description = "Base32 secret — manual-entry fallback if the QR code can't be scanned.") String secret,
            @Schema(description = "otpauth:// URI encoded in the QR code.") String otpauthUri,
            @Schema(description = "QR code as a data: URI (image/png;base64) — render directly in an <img> tag.") String qrCodeDataUri) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,}$",
                    message = "Password must be at least 10 characters and include uppercase, lowercase, number, and special character")
            String newPassword) {}

    public record BlockIpRequest(
            @NotBlank @Size(max = 64) String ipAddress,
            @NotBlank @Size(max = 500) String reason,
            Instant blockedUntil,
            boolean permanent) {}

    public record UnlockUserRequest(@Size(max = 500) String note) {}

    public record SessionResponse(
            UUID id, String ipAddress, String userAgent, Instant createdAt, Instant expiresAt, boolean revoked) {}

    public record SecurityEventResponse(
            UUID id, UUID userId, String email, SecurityEventType eventType, SecurityEventSeverity severity,
            String ipAddress, String userAgent, String detailsJson, Instant createdAt) {}

    public record LoginAttemptResponse(
            UUID id, String email, UUID userId, String ipAddress, String userAgent, boolean success,
            LoginFailureReason failureReason, int riskScore, String country, String city,
            String deviceFingerprint, Instant createdAt) {}

    public record LockedUserResponse(
            UUID id, String email, String fullName, int failedLoginCount, Instant lockedUntil,
            boolean adminUnlockRequired, boolean suspiciousActivityFlag) {}
}
