package com.byonix.shoplink.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionSecurityValidatorTest {
    @Mock Environment environment;
    @Mock ApplicationArguments applicationArguments;

    @Test
    void failsWhenProdProfileAndExposeTokensEnabled() {
        AuthProperties auth = new AuthProperties();
        auth.setExposeTokensInResponse(true);
        LoginSecurityProperties login = new LoginSecurityProperties();
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment, auth, login);
        ReflectionTestUtils.setField(validator, "merchantRefreshExpirationDays", 7L);
        ReflectionTestUtils.setField(validator, "adminRefreshExpirationDays", 1L);

        assertThrows(IllegalStateException.class, () -> validator.run(applicationArguments));
    }

    @Test
    void failsWhenProdProfileAndMfaDevBypassEnabled() {
        AuthProperties auth = new AuthProperties();
        auth.setExposeTokensInResponse(false);
        LoginSecurityProperties login = new LoginSecurityProperties();
        login.setMfaDevBypass(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment, auth, login);
        ReflectionTestUtils.setField(validator, "merchantRefreshExpirationDays", 7L);
        ReflectionTestUtils.setField(validator, "adminRefreshExpirationDays", 1L);

        assertThrows(IllegalStateException.class, () -> validator.run(applicationArguments));
    }

    @Test
    void passesWhenNotProdProfileEvenIfExposeTokensEnabled() {
        AuthProperties auth = new AuthProperties();
        auth.setExposeTokensInResponse(true);
        LoginSecurityProperties login = new LoginSecurityProperties();
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment, auth, login);
        assertDoesNotThrow(() -> validator.run(applicationArguments));
    }

    @Test
    void failsWhenAdminRefreshLifetimeNotShorterThanMerchantInProd() {
        AuthProperties auth = new AuthProperties();
        auth.setExposeTokensInResponse(false);
        LoginSecurityProperties login = new LoginSecurityProperties();
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment, auth, login);
        ReflectionTestUtils.setField(validator, "merchantRefreshExpirationDays", 7L);
        ReflectionTestUtils.setField(validator, "adminRefreshExpirationDays", 7L);

        assertThrows(IllegalStateException.class, () -> validator.run(applicationArguments));
    }
}
