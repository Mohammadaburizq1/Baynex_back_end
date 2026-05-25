package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminMfaFlowTest {
    @Test
    void mfaChallengeResponseHasNoAuthTokens() {
        SecurityDtos.AdminLoginResponse response = SecurityDtos.AdminLoginResponse.mfaChallenge("challenge-jwt");
        assertTrue(response.mfaRequired());
        assertEquals("challenge-jwt", response.mfaChallengeToken());
        assertNull(response.auth());
    }

    @Test
    void authenticatedResponseHasNoMfaChallenge() {
        AuthDtos.AuthResponse auth = new AuthDtos.AuthResponse("access", "refresh", null, null, null);
        SecurityDtos.AdminLoginResponse response = SecurityDtos.AdminLoginResponse.authenticated(auth);
        assertFalse(response.mfaRequired());
        assertNull(response.mfaChallengeToken());
        assertNotNull(response.auth());
        assertEquals("access", response.auth().accessToken());
    }

    @Test
    void superAdminRequiresMfaBeforeTokens() {
        AuthProperties props = new AuthProperties();
        props.setRequireSuperAdminMfa(true);
        MfaChallengeService service = new MfaChallengeService(null, null, props, null);
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);
        assertTrue(service.requiresMfaBeforeTokens(admin));
    }
}
