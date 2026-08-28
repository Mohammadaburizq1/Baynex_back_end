package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.config.MailProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.*;
import com.byonix.shoplink.security.JwtService;
import com.byonix.shoplink.security.RefreshTokenCookieService;
import com.byonix.shoplink.security.RefreshTokenCredentialResolver;
import com.byonix.shoplink.security.google.GoogleTokenVerifier;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import com.byonix.shoplink.service.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceGoogleLoginTest {
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock TokenHashService tokenHashService;
    @Mock SecureLoginService secureLoginService;
    @Mock RefreshTokenSecurityService refreshTokenSecurityService;
    @Mock RateLimitService rateLimitService;
    @Mock SecurityEventService securityEventService;
    @Mock AuthResponseFactory authResponseFactory;
    @Mock RefreshTokenCookieService refreshTokenCookieService;
    @Mock RefreshTokenCredentialResolver refreshTokenCredentialResolver;
    @Mock DevTokenLogger devTokenLogger;
    @Mock EmailNotificationService emailNotificationService;
    @Mock GoogleTokenVerifier googleTokenVerifier;
    @Mock PasswordHashService passwordHashService;
    @Mock OtpService otpService;
    @Mock HttpServletRequest httpServletRequest;
    @Mock HttpServletResponse httpServletResponse;

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                emailVerificationTokenRepository,
                passwordResetTokenRepository,
                passwordEncoder,
                jwtService,
                tokenHashService,
                secureLoginService,
                refreshTokenSecurityService,
                rateLimitService,
                securityEventService,
                authResponseFactory,
                refreshTokenCookieService,
                refreshTokenCredentialResolver,
                devTokenLogger,
                emailNotificationService,
                new MailProperties(),
                googleTokenVerifier,
                passwordHashService,
                otpService);

        lenient().when(httpServletRequest.getRemoteAddr()).thenReturn("203.0.113.20");
        lenient().when(tokenHashService.randomRefreshToken()).thenReturn("raw-refresh-token");
        lenient().when(authResponseFactory.authenticated(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AuthDtos.AuthResponse("access", "refresh", null, 0, false));
    }

    @Test
    void createsNewMerchantWhenNoAccountMatches() {
        var identity = new GoogleTokenVerifier.GoogleIdentity("google-sub-1", "new@example.com", "New Merchant");
        when(googleTokenVerifier.verify("valid-token")).thenReturn(identity);
        when(userRepository.findByGoogleSub("google-sub-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
        when(passwordHashService.generateUnusablePasswordHash()).thenReturn("$2a$12$unusable");

        authService.googleLogin(new AuthDtos.GoogleLoginRequest("valid-token"), httpServletRequest, httpServletResponse);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("new@example.com", saved.getEmail());
        assertEquals("google-sub-1", saved.getGoogleSub());
        assertEquals(Role.MERCHANT_OWNER, saved.getRole());
        assertEquals("$2a$12$unusable", saved.getPasswordHash());
        assertNotNull(saved.getEmailVerifiedAt());
        assertEquals("New Merchant", saved.getFullName());
    }

    @Test
    void linksGoogleSubToExistingAccountMatchedByEmail() {
        User existing = new User();
        existing.setId(UUID.randomUUID());
        existing.setEmail("owner@example.com");
        existing.setRole(Role.MERCHANT_OWNER);

        var identity = new GoogleTokenVerifier.GoogleIdentity("google-sub-2", "owner@example.com", "Owner");
        when(googleTokenVerifier.verify("valid-token")).thenReturn(identity);
        when(userRepository.findByGoogleSub("google-sub-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(existing));

        authService.googleLogin(new AuthDtos.GoogleLoginRequest("valid-token"), httpServletRequest, httpServletResponse);

        assertEquals("google-sub-2", existing.getGoogleSub());
        assertNotNull(existing.getEmailVerifiedAt());
        verify(userRepository, times(1)).save(any());
        verify(userRepository).save(existing);
    }

    @Test
    void logsInDirectlyWhenGoogleSubAlreadyLinked() {
        User existing = new User();
        existing.setId(UUID.randomUUID());
        existing.setEmail("owner@example.com");
        existing.setGoogleSub("google-sub-3");
        existing.setRole(Role.MERCHANT_OWNER);

        var identity = new GoogleTokenVerifier.GoogleIdentity("google-sub-3", "owner@example.com", "Owner");
        when(googleTokenVerifier.verify("valid-token")).thenReturn(identity);
        when(userRepository.findByGoogleSub("google-sub-3")).thenReturn(Optional.of(existing));

        authService.googleLogin(new AuthDtos.GoogleLoginRequest("valid-token"), httpServletRequest, httpServletResponse);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void rejectsInvalidToken() {
        when(googleTokenVerifier.verify("garbage")).thenThrow(new GenericAuthException());

        assertThrows(GenericAuthException.class, () -> authService.googleLogin(
                new AuthDtos.GoogleLoginRequest("garbage"), httpServletRequest, httpServletResponse));

        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsEmailCollisionWithNonMerchantAccount() {
        User customer = new User();
        customer.setId(UUID.randomUUID());
        customer.setEmail("shared@example.com");
        customer.setRole(Role.CUSTOMER);

        var identity = new GoogleTokenVerifier.GoogleIdentity("google-sub-4", "shared@example.com", "Someone");
        when(googleTokenVerifier.verify("valid-token")).thenReturn(identity);
        when(userRepository.findByGoogleSub("google-sub-4")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("shared@example.com")).thenReturn(Optional.of(customer));

        assertThrows(GenericAuthException.class, () -> authService.googleLogin(
                new AuthDtos.GoogleLoginRequest("valid-token"), httpServletRequest, httpServletResponse));

        verify(userRepository, never()).save(any());
    }
}
