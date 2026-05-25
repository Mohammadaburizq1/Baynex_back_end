package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.domain.entity.IpBlocklistEntry;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.AdminAuditAction;
import com.byonix.shoplink.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminSecurityServiceAuditTest {
    @Mock LoginAttemptService loginAttemptService;
    @Mock SecurityEventService securityEventService;
    @Mock UserRepository userRepository;
    @Mock AccountLockService accountLockService;
    @Mock IpBlocklistService ipBlocklistService;
    @Mock RefreshTokenSecurityService refreshTokenSecurityService;
    @Mock AdminAuditService adminAuditService;
    @InjectMocks AdminSecurityService adminSecurityService;

    User admin;
    User target;
    UUID targetId;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(UUID.randomUUID());
        target = new User();
        targetId = UUID.randomUUID();
        target.setId(targetId);
        target.setTokenVersion(1);
    }

    @Test
    void unlockUserCreatesAuditLog() {
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        adminSecurityService.unlockUser(admin, targetId, "10.0.0.1");
        verify(adminAuditService).log(admin, AdminAuditAction.UNLOCK_USER, targetId, "10.0.0.1", null);
    }

    @Test
    void forcePasswordResetCreatesAuditLog() {
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        adminSecurityService.forcePasswordReset(admin, targetId, "10.0.0.1");
        verify(adminAuditService).log(admin, AdminAuditAction.FORCE_PASSWORD_RESET, targetId, "10.0.0.1", null);
    }

    @Test
    void blockIpCreatesAuditLog() {
        SecurityDtos.BlockIpRequest request = new SecurityDtos.BlockIpRequest("203.0.113.1", "abuse", null, false);
        IpBlocklistEntry entry = new IpBlocklistEntry();
        entry.setIpAddress("203.0.113.1");
        entry.setReason("abuse");
        entry.setPermanent(false);
        when(ipBlocklistService.block(any(), any(), any(), anyBoolean(), eq(admin))).thenReturn(entry);

        adminSecurityService.blockIp(admin, request, "10.0.0.1");
        verify(adminAuditService).log(eq(admin), eq(AdminAuditAction.BLOCK_IP), isNull(), eq("203.0.113.1"), any());
    }

    @Test
    void unblockIpCreatesAuditLog() {
        adminSecurityService.unblockIp(admin, "203.0.113.1", "10.0.0.1");
        verify(adminAuditService).log(admin, AdminAuditAction.UNBLOCK_IP, null, "203.0.113.1", null);
    }
}
