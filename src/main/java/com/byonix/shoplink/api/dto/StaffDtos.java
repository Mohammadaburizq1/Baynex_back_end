package com.byonix.shoplink.api.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StaffDtos {
    private StaffDtos() {}

    public record InviteStaffRequest(
            @NotNull UUID storeId,
            @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 160) String fullName) {}

    // Never includes the raw invite token/link — same convention as email-verification and
    // password-reset (see DevTokenLogger). The invite is delivered by email (best-effort) and,
    // in local dev, logged server-side under app.auth.expose-tokens-in-response.
    public record InviteResponse(UUID id, String email, String fullName, Instant expiresAt) {}

    public record PendingInviteResponse(UUID id, String email, String fullName, Instant expiresAt, Instant createdAt) {}

    public record StaffMemberResponse(UUID id, String fullName, String email, boolean active, Instant createdAt) {}

    public record StaffListResponse(List<StaffMemberResponse> members, List<PendingInviteResponse> pendingInvites) {}

    public record AcceptInviteRequest(
            @NotBlank String token,
            @Size(max = 160) String fullName,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,}$",
                    message = "Password must be at least 10 characters and include uppercase, lowercase, number, and special character") String password) {}
}
