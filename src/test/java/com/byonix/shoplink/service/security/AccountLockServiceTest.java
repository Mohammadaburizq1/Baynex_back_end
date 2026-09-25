package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountLockServiceTest {
    @Mock UserRepository userRepository;
    @Mock LoginSecurityProperties props;
    @Mock SecurityEventService securityEventService;
    @InjectMocks AccountLockService accountLockService;

    @Test
    void locksAfterFiveFailures() {
        User user = new User();
        user.setRole(Role.MERCHANT_OWNER);
        user.setFailedLoginCount(4);
        org.mockito.Mockito.when(props.getMerchantLockThreshold1()).thenReturn(5);
        org.mockito.Mockito.when(props.getMerchantLockThreshold2()).thenReturn(10);
        org.mockito.Mockito.when(props.getMerchantLockThreshold3()).thenReturn(20);
        org.mockito.Mockito.when(props.getLockMinutes1()).thenReturn(15);

        accountLockService.recordFailedLogin(user, "1.2.3.4", "agent");
        assertTrue(user.getFailedLoginCount() >= 5);
        assertTrue(user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now()));
        verify(userRepository).save(user);
    }
}
