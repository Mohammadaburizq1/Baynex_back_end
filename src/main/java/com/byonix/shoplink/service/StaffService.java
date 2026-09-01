package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.api.dto.StaffDtos;
import com.byonix.shoplink.domain.entity.StaffInvite;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.StaffInviteRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import com.byonix.shoplink.service.security.DevTokenLogger;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffService {
    private final UserRepository userRepository;
    private final StaffInviteRepository staffInviteRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final PasswordEncoder passwordEncoder;
    private final TokenHashService tokenHashService;
    private final AuthService authService;
    private final EmailNotificationService emailNotificationService;
    private final DevTokenLogger devTokenLogger;
    private final RateLimitService rateLimitService;

    @Value("${app.auth.staff-invite-expiration-hours:72}")
    private long inviteExpirationHours;

    // Owner-only (ownedStore() below throws for anyone else, including staff of the same store —
    // inviting teammates is a store-settings-level action, not a day-to-day one).
    @Transactional
    public StaffDtos.InviteResponse inviteStaff(StaffDtos.InviteStaffRequest request) {
        Store store = storeService.ownedStore(request.storeId());
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("A user with this email already exists");
        }
        // Superseding a still-pending invite to the same email for the same store, rather than
        // accumulating duplicates, so re-inviting after a typo'd name just works.
        staffInviteRepository.deleteByStore_IdAndEmailIgnoreCaseAndConsumedAtIsNull(store.getId(), email);

        String raw = tokenHashService.randomRefreshToken();
        StaffInvite invite = new StaffInvite();
        invite.setStore(store);
        invite.setInvitedBy(currentUser.user());
        invite.setEmail(email);
        invite.setFullName(blankToNull(request.fullName()));
        invite.setTokenHash(tokenHashService.hash(raw));
        invite.setExpiresAt(Instant.now().plusSeconds(inviteExpirationHours * 3600));
        staffInviteRepository.save(invite);

        devTokenLogger.logStaffInviteToken(email, raw);
        emailNotificationService.sendStaffInvite(email, store.getName(), raw);

        return new StaffDtos.InviteResponse(invite.getId(), email, invite.getFullName(), invite.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public StaffDtos.StaffListResponse listStaffAndInvites(UUID storeId) {
        Store store = storeService.ownedStore(storeId);
        List<StaffDtos.StaffMemberResponse> members = userRepository
                .findByStore_IdAndRoleOrderByCreatedAtAsc(store.getId(), Role.MERCHANT_STAFF).stream()
                .map(u -> new StaffDtos.StaffMemberResponse(u.getId(), u.getFullName(), u.getEmail(), u.isActive(), u.getCreatedAt()))
                .toList();
        List<StaffDtos.PendingInviteResponse> pending = staffInviteRepository
                .findByStore_IdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(store.getId(), Instant.now()).stream()
                .map(i -> new StaffDtos.PendingInviteResponse(i.getId(), i.getEmail(), i.getFullName(), i.getExpiresAt(), i.getCreatedAt()))
                .toList();
        return new StaffDtos.StaffListResponse(members, pending);
    }

    // Owner-only — cancels a not-yet-accepted invite. Ownership is checked via the invite's own
    // store (ownedStore throws if this caller doesn't own it), not a client-supplied storeId, so
    // there's no way to pass a mismatched id and act on someone else's invite.
    @Transactional
    public void revokeInvite(UUID inviteId) {
        StaffInvite invite = staffInviteRepository.findById(inviteId)
                .orElseThrow(() -> new EntityNotFoundException("Invite not found"));
        storeService.ownedStore(invite.getStore().getId());
        if (invite.getConsumedAt() != null) {
            throw new IllegalArgumentException("This invite has already been accepted");
        }
        staffInviteRepository.delete(invite);
    }

    // Owner-only — soft removal (User.active, the same flag the rest of the app already gates
    // login on). JwtAuthenticationFilter checks isActive() on every request, so this takes effect
    // immediately rather than waiting for their session to expire. No hard delete: reversible,
    // and the account's order/audit history stays intact.
    @Transactional
    public void deactivateStaff(UUID staffUserId) {
        User staff = userRepository.findById(staffUserId)
                .orElseThrow(() -> new EntityNotFoundException("Staff member not found"));
        if (staff.getRole() != Role.MERCHANT_STAFF || staff.getStore() == null) {
            throw new IllegalArgumentException("Not a staff member");
        }
        storeService.ownedStore(staff.getStore().getId());
        staff.setActive(false);
        userRepository.save(staff);
    }

    // Public — the caller isn't authenticated yet (that's the whole point of the invite link).
    // Possessing a valid, unexpired, unconsumed token is the proof of identity here, the same
    // trust model as email-verification and password-reset tokens elsewhere in this service.
    @Transactional
    public AuthDtos.AuthResponse acceptInvite(StaffDtos.AcceptInviteRequest request, HttpServletRequest http,
                                              HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkRegisterByIp(ctx.ipAddress());

        StaffInvite invite = staffInviteRepository.findByTokenHash(tokenHashService.hash(request.token()))
                .orElseThrow(SecurityActionException::invalidToken);
        if (invite.getConsumedAt() != null || invite.getExpiresAt().isBefore(Instant.now())) {
            throw SecurityActionException.invalidToken();
        }
        if (userRepository.existsByEmailIgnoreCase(invite.getEmail())) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        String name = request.fullName() != null && !request.fullName().isBlank()
                ? request.fullName().trim()
                : (invite.getFullName() != null ? invite.getFullName() : "Team member");

        User user = new User();
        user.setFullName(name);
        user.setEmail(invite.getEmail());
        user.setRole(Role.MERCHANT_STAFF);
        user.setStore(invite.getStore());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordChangedAt(Instant.now());
        // Possessing the invite link (sent only to the invited address) stands in for email
        // verification here, same as the phone-signup OTP flow stands in for phone verification.
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        invite.setConsumedAt(Instant.now());
        staffInviteRepository.save(invite);

        return authService.issue(user, http, response, 0, false, RefreshSessionScope.MERCHANT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
