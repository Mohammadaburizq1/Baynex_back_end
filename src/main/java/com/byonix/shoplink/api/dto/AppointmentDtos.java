package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.AppointmentStatus;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AppointmentDtos {
    private AppointmentDtos() {}

    public record SlotRequest(
            @NotNull UUID storeId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @Positive Integer capacity,
            Boolean active) {}

    public record SlotResponse(UUID id, UUID storeId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                               int capacity, int bookedCount, boolean active) {}

    public record CreateAppointmentRequest(
            @NotNull UUID slotId,
            UUID productId,
            @NotBlank @Size(max = 160) String customerName,
            @Email @Size(max = 255) String customerEmail,
            @NotBlank @Pattern(regexp = "^\\+?[0-9\\s\\-()]{7,40}$", message = "Invalid phone number") String customerPhone,
            @Size(max = 1000) String notes) {}

    public record StatusUpdateRequest(@NotNull AppointmentStatus status) {}

    public record AppointmentResponse(UUID id, UUID storeId, UUID slotId, OffsetDateTime slotStartsAt,
                                      OffsetDateTime slotEndsAt, UUID productId, String productName,
                                      String customerName, String customerEmail, String customerPhone,
                                      String notes, AppointmentStatus status, Instant createdAt) {}
}
