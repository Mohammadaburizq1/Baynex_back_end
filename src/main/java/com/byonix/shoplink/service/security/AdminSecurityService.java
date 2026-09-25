package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.domain.entity.IpBlocklistEntry;
import com.byonix.shoplink.domain.entity.LoginAttempt;
import com.byonix.shoplink.domain.entity.SecurityEvent;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.AdminAuditAction;
import com.byonix.shoplink.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminSecurityService {
    private final LoginAttemptService loginAttemptService;
    private final SecurityEventService securityEventService;
    private final UserRepository userRepository;
    private final AccountLockService accountLockService;
    private final IpBlocklistService ipBlocklistService;
    private final RefreshTokenSecurityService refreshTokenSecurityService;
    private final AdminAuditService adminAuditService;

    public Page<LoginAttempt> loginAttempts(Pageable pageable) {
        return loginAttemptService.all(pageable);
    }

    public Page<SecurityEvent> securityEvents(Pageable pageable) {
        return securityEventService.all(pageable);
    }

    public Page<LoginAttempt> suspiciousActivity(Pageable pageable) {
        return loginAttemptService.suspicious(pageable);
    }

    public Page<User> lockedUsers(Pageable pageable) {
        return userRepository.findLockedUsers(Instant.now(), pageable);
    }

    @Transactional
    public void unlockUser(User admin, UUID userId, String ip) {
        User target = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        accountLockService.adminUnlock(target, admin, ip);
        adminAuditService.log(admin, AdminAuditAction.UNLOCK_USER, userId, ip, null);
    }

    @Transactional
    public void forcePasswordReset(User admin, UUID userId, String ip) {
        User target = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
        accountLockService.forcePasswordReset(target);
        refreshTokenSecurityService.revokeAllSessions(target);
        target.setTokenVersion(target.getTokenVersion() + 1);
        userRepository.save(target);
        adminAuditService.log(admin, AdminAuditAction.FORCE_PASSWORD_RESET, userId, ip, null);
    }

    @Transactional
    public IpBlocklistEntry blockIp(User admin, SecurityDtos.BlockIpRequest request, String ip) {
        IpBlocklistEntry entry = ipBlocklistService.block(
                request.ipAddress(),
                request.reason(),
                request.blockedUntil(),
                Boolean.TRUE.equals(request.permanent()),
                admin);
        adminAuditService.log(admin, AdminAuditAction.BLOCK_IP, null, request.ipAddress(),
                Map.of("reason", request.reason()));
        return entry;
    }

    @Transactional
    public void unblockIp(User admin, String targetIp, String adminIp) {
        ipBlocklistService.unblock(targetIp);
        adminAuditService.log(admin, AdminAuditAction.UNBLOCK_IP, null, targetIp, null);
    }

    public void auditViewSuspicious(User admin, String ip) {
        adminAuditService.log(admin, AdminAuditAction.VIEW_SUSPICIOUS_ACTIVITY, null, ip, null);
    }
}
