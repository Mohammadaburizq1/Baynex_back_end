package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.PosDeviceStatus;
import com.byonix.shoplink.domain.enums.StoreStatus;
import com.byonix.shoplink.repository.CategoryRepository;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * POS device registration (POS-02) and the device-scoped catalog snapshot (POS-04).
 *
 * Trust model: the owner creates a device in the dashboard and gets a single-use activation code
 * (15 minutes). The POS exchanges it for a device credential that only authorizes {@code /api/pos/**}
 * reads for that one store. The credential slides: each authenticated call pushes its expiry out to
 * {@link #CREDENTIAL_IDLE_LIFETIME}, so a device that stays offline longer than that must be
 * re-activated. Revocation is immediate on the server. Only hashes are stored.
 */
@Service
@RequiredArgsConstructor
public class PosDeviceService {
    static final Duration ACTIVATION_CODE_LIFETIME = Duration.ofMinutes(15);
    static final Duration CREDENTIAL_IDLE_LIFETIME = Duration.ofDays(30);
    public static final String CREDENTIAL_PREFIX = "kgpos_";
    // No 0/O, 1/I/L: codes are read off one screen and typed into another.
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final PosDeviceRepository deviceRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final TokenHashService tokenHashService;
    private final RateLimitService rateLimitService;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductAssembler assembler;
    private final MapperService mapper;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    // ── dashboard (store owner) ───────────────────────────────────────────────────────────────

    @Transactional
    public PosDtos.IssuedActivationCode create(PosDtos.CreateDeviceRequest request) {
        Store store = storeService.ownedStore(request.storeId());
        PosDevice device = new PosDevice();
        device.setStore(store);
        device.setName(request.name().trim());
        device.setCreatedBy(currentUser.user());
        return issueCode(deviceRepository.save(device));
    }

    public List<PosDtos.DeviceResponse> list(UUID storeId) {
        storeService.ownedStore(storeId);
        return deviceRepository.findByStore_IdOrderByCreatedAtDesc(storeId).stream().map(PosDeviceService::toResponse).toList();
    }

    /** A new code for a device that is waiting, or whose credential expired and needs re-pairing. */
    @Transactional
    public PosDtos.IssuedActivationCode reissueCode(UUID deviceId) {
        PosDevice device = ownedDevice(deviceId);
        if (device.getStatus() == PosDeviceStatus.REVOKED) {
            throw new IllegalArgumentException("A revoked device cannot be activated again; register a new device");
        }
        return issueCode(device);
    }

    @Transactional
    public PosDtos.DeviceResponse revoke(UUID deviceId) {
        PosDevice device = ownedDevice(deviceId);
        device.setStatus(PosDeviceStatus.REVOKED);
        device.setRevokedAt(Instant.now());
        device.setCredentialHash(null);
        device.setCredentialExpiresAt(null);
        device.setActivationCodeHash(null);
        device.setActivationCodeExpiresAt(null);
        return toResponse(device);
    }

    // ── device ────────────────────────────────────────────────────────────────────────────────

    /** Every failure answers the same way, so the endpoint cannot be used to probe codes. */
    @Transactional
    public PosDtos.ActivationResponse activate(PosDtos.ActivateRequest request, String ip) {
        rateLimitService.checkOrThrow("pos-activate-ip", ip, 10, 300);
        Instant now = Instant.now();
        PosDevice device = deviceRepository.findByActivationCodeHash(tokenHashService.hash(normalizeCode(request.activationCode())))
                .filter(d -> d.getStatus() != PosDeviceStatus.REVOKED)
                .filter(d -> d.getActivationCodeExpiresAt() != null && d.getActivationCodeExpiresAt().isAfter(now))
                .orElseThrow(SecurityActionException::invalidToken);
        String credential = CREDENTIAL_PREFIX + tokenHashService.randomRefreshToken();
        device.setCredentialHash(tokenHashService.hash(credential));
        device.setCredentialExpiresAt(now.plus(CREDENTIAL_IDLE_LIFETIME));
        device.setActivationCodeHash(null);
        device.setActivationCodeExpiresAt(null);
        device.setStatus(PosDeviceStatus.ACTIVE);
        device.setActivatedAt(now);
        device.setLastSeenAt(now);
        device.setInstallationId(request.installationId().trim());
        device.setPlatform(blankToNull(request.platform()));
        device.setAppVersion(blankToNull(request.appVersion()));
        return new PosDtos.ActivationResponse(device.getId(), device.getName(), credential,
                device.getCredentialExpiresAt(), posStore(device.getStore()), now);
    }

    public enum CredentialProblem { INVALID, EXPIRED, REVOKED, STORE_UNAVAILABLE }

    public record CredentialCheck(PosDevicePrincipal principal, CredentialProblem problem) {}

    /** Used by the POS device filter on every {@code /api/pos/**} call. */
    @Transactional
    public CredentialCheck authenticate(String credential) {
        if (credential == null || !credential.startsWith(CREDENTIAL_PREFIX)) {
            return new CredentialCheck(null, CredentialProblem.INVALID);
        }
        Optional<PosDevice> found = deviceRepository.findByCredentialHash(tokenHashService.hash(credential));
        if (found.isEmpty()) return new CredentialCheck(null, CredentialProblem.INVALID);
        PosDevice device = found.get();
        Instant now = Instant.now();
        if (device.getStatus() != PosDeviceStatus.ACTIVE) return new CredentialCheck(null, CredentialProblem.REVOKED);
        if (device.getCredentialExpiresAt() == null || !device.getCredentialExpiresAt().isAfter(now)) {
            return new CredentialCheck(null, CredentialProblem.EXPIRED);
        }
        Store store = device.getStore();
        if (store.getStatus() == StoreStatus.SUSPENDED || !store.getOwner().isActive()) {
            return new CredentialCheck(null, CredentialProblem.STORE_UNAVAILABLE);
        }
        // Sliding idle expiry; written at most once a minute per device.
        if (device.getLastSeenAt() == null || device.getLastSeenAt().isBefore(now.minusSeconds(60))) {
            device.setLastSeenAt(now);
            device.setCredentialExpiresAt(now.plus(CREDENTIAL_IDLE_LIFETIME));
        }
        return new CredentialCheck(new PosDevicePrincipal(device.getId(), store.getId()), null);
    }

    public PosDtos.DeviceSession session(PosDevicePrincipal principal) {
        PosDevice device = device(principal);
        return new PosDtos.DeviceSession(device.getId(), device.getName(), device.getStatus(), posStore(device.getStore()),
                device.getCredentialExpiresAt(), device.getLastSyncAt(), Instant.now());
    }

    /**
     * The store is taken from the authenticated device, never from the request. Categories and
     * products that are disabled on the server are omitted, so a sync removes them from the POS.
     * Products use the dashboard view (exact stock) — the same assembly the merchant dashboard reads.
     */
    @Transactional
    public PosDtos.CatalogResponse catalog(PosDevicePrincipal principal, String knownVersion) {
        PosDevice device = device(principal);
        Store store = device.getStore();
        var categories = categoryRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(store.getId()).stream()
                .filter(c -> c.isActive()).map(mapper::category).toList();
        List<Product> available = productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(store.getId()).stream()
                .filter(Product::isAvailable).toList();
        var products = assembler.dashboard(available);
        PosDtos.PosStore posStore = posStore(store);
        String version = contentHash(posStore, categories, products);
        Instant now = Instant.now();
        device.setLastSyncAt(now);
        if (version.equals(knownVersion)) {
            return new PosDtos.CatalogResponse(version, true, now, null, null, null);
        }
        return new PosDtos.CatalogResponse(version, false, now, posStore, categories, products);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private PosDtos.IssuedActivationCode issueCode(PosDevice device) {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 10; i++) raw.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        Instant expiresAt = Instant.now().plus(ACTIVATION_CODE_LIFETIME);
        device.setActivationCodeHash(tokenHashService.hash(raw.toString()));
        device.setActivationCodeExpiresAt(expiresAt);
        return new PosDtos.IssuedActivationCode(toResponse(device), raw.substring(0, 5) + "-" + raw.substring(5), expiresAt);
    }

    static String normalizeCode(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private PosDevice ownedDevice(UUID deviceId) {
        PosDevice device = deviceRepository.findById(deviceId).orElseThrow(() -> new EntityNotFoundException("Device not found"));
        storeService.ownedStore(device.getStore().getId());
        return device;
    }

    private PosDevice device(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId())
                .orElseThrow(() -> new EntityNotFoundException("Device not found"));
        if (!device.getStore().getId().equals(principal.storeId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        return device;
    }

    private String contentHash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) digest.update(objectMapper.writeValueAsString(part).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static PosDtos.PosStore posStore(Store s) {
        return new PosDtos.PosStore(s.getId(), s.getSlug(), s.getName(), s.getCurrency(), s.getTimezone(), s.getLocale(),
                s.getTemplateKey(), s.getCategorySlug(), s.getStatus(), s.isAcceptingOrders(), s.isPickupAvailable());
    }

    private static PosDtos.DeviceResponse toResponse(PosDevice d) {
        return new PosDtos.DeviceResponse(d.getId(), d.getStore().getId(), d.getName(), d.getStatus(), d.getPlatform(),
                d.getAppVersion(), d.getActivationCodeExpiresAt(), d.getActivatedAt(), d.getLastSeenAt(), d.getLastSyncAt(),
                d.getRevokedAt(), d.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
