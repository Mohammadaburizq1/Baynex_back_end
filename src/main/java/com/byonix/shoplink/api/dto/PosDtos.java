package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.PosDeviceStatus;
import com.byonix.shoplink.domain.enums.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** POS device registration (POS-02) and the device-scoped catalog sync payload (POS-04). */
public class PosDtos {
    public record CreateDeviceRequest(@NotNull UUID storeId, @NotBlank @Size(max = 80) String name) {}

    public record DeviceResponse(UUID id, UUID storeId, String name, PosDeviceStatus status, String platform,
                                 String appVersion, Instant activationCodeExpiresAt, Instant activatedAt,
                                 Instant lastSeenAt, Instant lastSyncAt, Instant revokedAt, Instant createdAt) {}

    /** The only response that ever carries the plaintext activation code. */
    public record IssuedActivationCode(DeviceResponse device, String activationCode, Instant expiresAt) {}

    public record ActivateRequest(@NotBlank @Size(max = 32) String activationCode,
                                  @NotBlank @Size(max = 64) String installationId,
                                  @Size(max = 20) String platform,
                                  @Size(max = 40) String appVersion) {}

    /** What a POS needs to know about its store. Owner/contact/billing data is deliberately absent. */
    public record PosStore(UUID id, String slug, String name, String currency, String timezone, String locale,
                           String templateKey, String categorySlug, StoreStatus status, boolean acceptingOrders,
                           boolean pickupAvailable) {}

    /** The only response that ever carries the plaintext device credential. */
    public record ActivationResponse(UUID deviceId, String deviceName, String deviceCredential,
                                     Instant credentialExpiresAt, PosStore store, Instant serverTime) {}

    public record DeviceSession(UUID deviceId, String deviceName, PosDeviceStatus status, PosStore store,
                                Instant credentialExpiresAt, Instant lastSyncAt, Instant serverTime) {}

    /**
     * Full catalog snapshot for the device's store. {@code catalogVersion} is a content hash: a POS that
     * sends it back as {@code knownVersion} gets {@code unchanged=true} and no body when nothing changed.
     */
    public record CatalogResponse(String catalogVersion, boolean unchanged, Instant generatedAt, PosStore store,
                                  List<CategoryDtos.CategoryResponse> categories,
                                  List<ProductDtos.ProductResponse> products) {}
}
