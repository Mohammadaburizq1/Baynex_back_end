package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccountLockService {
    private final UserRepository userRepository;
    private final LoginSecurityProperties props;
    private final SecurityEventService securityEventService;

    public boolean isLocked(User user) {
        if (user.isAdminUnlockRequired()) {
            return true;
        }
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now());
    }

    @Transactional
    public void recordFailedLogin(User user, String ip, String userAgent) {
        user.setFailedLoginCount(user.getFailedLoginCount() + 1);
        user.setLastFailedLoginAt(Instant.now());
        applyLockPolicy(user);
        userRepository.save(user);
        if (isLocked(user)) {
            securityEventService.log(SecurityEventType.ACCOUNT_LOCKED, SecurityEventSeverity.WARN, user, null,
                    ip, userAgent, Map.of("failedCount", user.getFailedLoginCount()));
        }
    }

    @Transactional
    public void recordSuccessfulLogin(User user, String ip, String userAgent) {
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setAdminUnlockRequired(false);
        user.setLastSuccessfulLoginAt(Instant.now());
        user.setLastLoginIp(ip);
        user.setLastLoginUserAgent(userAgent);
        userRepository.save(user);
    }

    @Transactional
    public void adminUnlock(User user, User admin, String ip) {
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setAdminUnlockRequired(false);
        userRepository.save(user);
        securityEventService.log(SecurityEventType.ACCOUNT_UNLOCKED, SecurityEventSeverity.INFO, user, null,
                ip, null, Map.of("unlockedBy", admin.getId().toString()));
    }

    @Transactional
    public void forcePasswordReset(User user) {
        user.setForcePasswordReset(true);
        userRepository.save(user);
    }

    private void applyLockPolicy(User user) {
        int count = user.getFailedLoginCount();
        boolean admin = user.getRole().isAdmin();
        int t1 = admin ? props.getAdminLockThreshold1() : props.getMerchantLockThreshold1();
        int t2 = admin ? props.getAdminLockThreshold2() : props.getMerchantLockThreshold2();
        int t3 = admin ? props.getAdminLockThreshold3() : props.getMerchantLockThreshold3();

        if (count >= t3) {
            user.setAdminUnlockRequired(true);
            user.setLockedUntil(null);
        } else if (count >= t2) {
            user.setLockedUntil(Instant.now().plusSeconds(props.getLockMinutes2() * 60L));
        } else if (count >= t1) {
            user.setLockedUntil(Instant.now().plusSeconds(props.getLockMinutes1() * 60L));
        }
    }
}
