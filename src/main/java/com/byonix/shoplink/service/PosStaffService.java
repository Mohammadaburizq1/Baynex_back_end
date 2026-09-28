package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosStaffPin;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosStaffPinRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * POS-14: who may use a store's POS, and their PINs.
 *
 * Staff are the existing khanGates people — the store owner and its MERCHANT_STAFF accounts — and
 * POS rights come from the existing permission grid (section POS: VIEW = cashier, EDIT = POS
 * manager; ORDERS and OFFERS as they already are). The owner is always a POS manager.
 *
 * A PIN is set online by the person themselves (confirming their account password) and stored only
 * as PBKDF2-HMAC-SHA256 with a random salt. The store's POS devices receive salt + hash + iterations
 * so they can verify the PIN with no network; the PIN itself never leaves the request that sets it.
 * PINs expire (PIN_LIFETIME): an offline credential is never permanent.
 */
@Service
@RequiredArgsConstructor
public class PosStaffService {
    static final int PBKDF2_ITERATIONS = 120_000;
    static final Duration PIN_LIFETIME = Duration.ofDays(180);

    private final PosStaffPinRepository pinRepository;
    private final PosDeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUser;
    private final PasswordEncoder passwordEncoder;
    private final com.byonix.shoplink.repository.StaffPermissionRepository permissionRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    // ── dashboard (online) ────────────────────────────────────────────────────────────────────

    public PosDtos.PinStatus myPin() {
        return status(pinRepository.findById(currentUser.user().getId()));
    }

    /** Sets or changes the caller's own PIN; their account password confirms it is really them. */
    @Transactional
    public PosDtos.PinStatus setMyPin(PosDtos.SetPinRequest request) {
        User user = currentUser.user();
        if (!user.getRole().isMerchant()) {
            throw new AccessDeniedException("Access denied");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("The password is not correct");
        }
        String pin = request.pin();
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) {
            throw new IllegalArgumentException("Choose a PIN that is not all the same digit or a simple sequence");
        }
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        PosStaffPin row = pinRepository.findById(user.getId()).orElseGet(PosStaffPin::new);
        Instant now = Instant.now();
        row.setUserId(user.getId());
        row.setPinSalt(HexFormat.of().formatHex(salt));
        row.setIterations(PBKDF2_ITERATIONS);
        row.setPinHash(HexFormat.of().formatHex(pbkdf2(pin, salt, PBKDF2_ITERATIONS)));
        row.setSetAt(now);
        row.setExpiresAt(now.plus(PIN_LIFETIME));
        return status(Optional.of(pinRepository.save(row)));
    }

    /** The owner removes a staff member's PIN (e.g. it was shared); they must set a new one online. */
    @Transactional
    public void clearStaffPin(UUID userId) {
        User caller = currentUser.user();
        User target = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        boolean self = caller.getId().equals(userId);
        boolean ownerOfStaff = target.getRole() == Role.MERCHANT_STAFF && target.getStore() != null
                && target.getStore().getOwner().getId().equals(caller.getId());
        if (!self && !ownerOfStaff) {
            throw new AccessDeniedException("Access denied");
        }
        pinRepository.deleteById(userId);
    }

    // ── device (POS) ──────────────────────────────────────────────────────────────────────────

    /**
     * The store's POS people with their POS rights and PIN hashes. Only this device's store: the
     * owner and the store's active staff. Nothing else about the accounts is sent.
     */
    @Transactional(readOnly = true)
    public PosDtos.StaffResponse staff(PosDevicePrincipal principal, String knownVersion) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        Store store = device.getStore();
        if (!store.getId().equals(principal.storeId())) {
            throw new AccessDeniedException("Access denied");
        }
        List<PosDtos.PosStaffMember> members = new ArrayList<>();
        User owner = store.getOwner();
        if (owner.isActive()) {
            members.add(member(owner, true));
        }
        for (User staff : userRepository.findByStore_IdAndRoleOrderByCreatedAtAsc(store.getId(), Role.MERCHANT_STAFF)) {
            if (staff.isActive()) {
                members.add(member(staff, false));
            }
        }
        String version = PosPriceBookService.sha256(writeJson(members)).substring(0, 32);
        Instant now = Instant.now();
        if (version.equals(knownVersion)) {
            return new PosDtos.StaffResponse(version, true, now, null);
        }
        return new PosDtos.StaffResponse(version, false, now, members);
    }

    /** POS-14 upload check: is this person a POS user of this store right now, and at what level? */
    @Transactional(readOnly = true)
    public Optional<PosDtos.PosStaffMember> currentMember(Store store, UUID userId) {
        if (userId == null) return Optional.empty();
        if (store.getOwner().getId().equals(userId)) {
            return store.getOwner().isActive() ? Optional.of(member(store.getOwner(), true)) : Optional.empty();
        }
        return userRepository.findById(userId)
                .filter(u -> u.isActive() && u.getRole() == Role.MERCHANT_STAFF && u.getStore() != null
                        && u.getStore().getId().equals(store.getId()))
                .map(u -> member(u, false));
    }

    private PosDtos.PosStaffMember member(User user, boolean owner) {
        Map<DashboardSection, PermissionLevel> grid = new java.util.EnumMap<>(DashboardSection.class);
        if (!owner) {
            permissionRepository.findByUser_Id(user.getId()).forEach(p -> grid.put(p.getSection(), p.getLevel()));
        }
        PermissionLevel pos = owner ? PermissionLevel.EDIT : grid.getOrDefault(DashboardSection.POS, CurrentUserService.defaultStaffLevel(DashboardSection.POS));
        PermissionLevel orders = owner ? PermissionLevel.EDIT : grid.getOrDefault(DashboardSection.ORDERS, CurrentUserService.defaultStaffLevel(DashboardSection.ORDERS));
        PermissionLevel offers = owner ? PermissionLevel.EDIT : grid.getOrDefault(DashboardSection.OFFERS, CurrentUserService.defaultStaffLevel(DashboardSection.OFFERS));
        PosDtos.PosPin pin = pinRepository.findById(user.getId())
                .filter(p -> p.getExpiresAt().isAfter(Instant.now()))
                .map(p -> new PosDtos.PosPin(p.getPinSalt(), p.getPinHash(), p.getIterations(), p.getExpiresAt()))
                .orElse(null);
        return new PosDtos.PosStaffMember(user.getId(), user.getFullName(), owner, pos, orders, offers, pin);
    }

    private PosDtos.PinStatus status(Optional<PosStaffPin> pin) {
        return pin.map(p -> new PosDtos.PinStatus(p.getExpiresAt().isAfter(Instant.now()), p.getSetAt(), p.getExpiresAt()))
                .orElse(new PosDtos.PinStatus(false, null, null));
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /** PBKDF2-HMAC-SHA256, 32-byte output — the same derivation the POS app runs offline. */
    static byte[] pbkdf2(String pin, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    static boolean matches(String pin, PosStaffPin row) {
        return MessageDigest.isEqual(pbkdf2(pin, HexFormat.of().parseHex(row.getPinSalt()), row.getIterations()),
                HexFormat.of().parseHex(row.getPinHash()));
    }
}
