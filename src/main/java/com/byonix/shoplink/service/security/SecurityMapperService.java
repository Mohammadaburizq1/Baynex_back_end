package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.domain.entity.LoginAttempt;
import com.byonix.shoplink.domain.entity.SecurityEvent;
import com.byonix.shoplink.domain.entity.User;
import org.springframework.stereotype.Service;

@Service
public class SecurityMapperService {
    public SecurityDtos.SecurityEventResponse event(SecurityEvent e) {
        return new SecurityDtos.SecurityEventResponse(
                e.getId(), e.getUserId(), e.getEmail(), e.getEventType(), e.getSeverity(),
                e.getIpAddress(), e.getUserAgent(), e.getDetailsJson(), e.getCreatedAt());
    }

    public SecurityDtos.LoginAttemptResponse attempt(LoginAttempt a) {
        return new SecurityDtos.LoginAttemptResponse(
                a.getId(), a.getEmail(), a.getUserId(), a.getIpAddress(), a.getUserAgent(),
                a.isSuccess(), a.getFailureReason(), a.getRiskScore(),
                a.getCountry(), a.getCity(), a.getDeviceFingerprint(), a.getCreatedAt());
    }

    public SecurityDtos.LockedUserResponse lockedUser(User u) {
        return new SecurityDtos.LockedUserResponse(
                u.getId(), u.getEmail(), u.getFullName(), u.getFailedLoginCount(),
                u.getLockedUntil(), u.isAdminUnlockRequired(), u.isSuspiciousActivityFlag());
    }
}
