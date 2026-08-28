package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.security.login.GenericAuthException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
        MfaChallengeService service = new MfaChallengeService(null, null, props,
                new LoginSecurityProperties(), mock(SecurityEventService.class), new TotpService());
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);
        assertTrue(service.requiresMfaBeforeTokens(admin));
    }

    @Test
    void validateTotpAcceptsCorrectCodeForEnrolledSecret() throws Exception {
        MfaChallengeService service = new MfaChallengeService(null, null, new AuthProperties(),
                new LoginSecurityProperties(), mock(SecurityEventService.class), new TotpService());
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);
        admin.setMfaSecret(new DefaultSecretGenerator().generate());
        long counter = new SystemTimeProvider().getTime() / 30;
        String code = new DefaultCodeGenerator().generate(admin.getMfaSecret(), counter);

        assertDoesNotThrow(() -> service.validateTotp(admin, code));
    }

    @Test
    void validateTotpRejectsWrongCode() {
        MfaChallengeService service = new MfaChallengeService(null, null, new AuthProperties(),
                new LoginSecurityProperties(), mock(SecurityEventService.class), new TotpService());
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);
        admin.setMfaSecret(new DefaultSecretGenerator().generate());

        assertThrows(GenericAuthException.class, () -> service.validateTotp(admin, "000000"));
    }

    @Test
    void devBypassCodeRejectedWhenFlagDisabled() {
        LoginSecurityProperties loginSecurityProperties = new LoginSecurityProperties();
        loginSecurityProperties.setMfaDevBypass(false);
        MfaChallengeService service = new MfaChallengeService(null, null, new AuthProperties(),
                loginSecurityProperties, mock(SecurityEventService.class), new TotpService());
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);
        admin.setMfaSecret(new DefaultSecretGenerator().generate());

        assertThrows(GenericAuthException.class, () -> service.validateTotp(admin, "000000"));
    }

    @Test
    void devBypassCodeAcceptedOnlyWhenFlagEnabled() {
        LoginSecurityProperties loginSecurityProperties = new LoginSecurityProperties();
        loginSecurityProperties.setMfaDevBypass(true);
        MfaChallengeService service = new MfaChallengeService(null, null, new AuthProperties(),
                loginSecurityProperties, mock(SecurityEventService.class), new TotpService());
        User admin = new User();
        admin.setRole(Role.SUPER_ADMIN);

        assertDoesNotThrow(() -> service.validateTotp(admin, "000000"));
    }
}
