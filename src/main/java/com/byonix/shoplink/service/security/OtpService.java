package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.entity.PhoneOtpCode;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.OtpPurpose;
import com.byonix.shoplink.repository.PhoneOtpCodeRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.service.TokenHashService;
import com.byonix.shoplink.util.PhoneNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Shared OTP generation/verification for phone signup verification and phone-based
 * password recovery — the only two points OTP is used (login stays password-only).
 */
@Service
@RequiredArgsConstructor
public class OtpService {
    private static final int EXPIRY_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;

    private static final String DEV_FIXED_CODE = "000000";

    private final PhoneOtpCodeRepository phoneOtpCodeRepository;
    private final UserRepository userRepository;
    private final TokenHashService tokenHashService;
    private final OtpSender otpSender;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public void generateAndSend(User user, OtpPurpose purpose) {
        phoneOtpCodeRepository.deleteByUserIdAndPurpose(user.getId(), purpose);

        String code = generateCode();
        PhoneOtpCode otp = new PhoneOtpCode();
        otp.setUser(user);
        otp.setPurpose(purpose);
        otp.setCodeHash(tokenHashService.hash(code));
        otp.setAttempts(0);
        otp.setExpiresAt(Instant.now().plusSeconds(EXPIRY_MINUTES * 60L));
        phoneOtpCodeRepository.save(otp);

        otpSender.send(user.getPhone(), code, purpose);
    }

    @Transactional
    public User verify(String phone, String code, OtpPurpose purpose) {
        String digits = PhoneNormalizer.digitsOnly(phone);
        User user = userRepository.findByPhoneDigits(digits)
                .orElseThrow(OtpService::invalidCode);

        PhoneOtpCode otp = phoneOtpCodeRepository
                .findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId(), purpose)
                .orElseThrow(OtpService::invalidCode);

        if (otp.getExpiresAt().isBefore(Instant.now()) || otp.getAttempts() >= MAX_ATTEMPTS) {
            throw invalidCode();
        }
        if (!otp.getCodeHash().equals(tokenHashService.hash(code))) {
            otp.setAttempts(otp.getAttempts() + 1);
            phoneOtpCodeRepository.save(otp);
            throw invalidCode();
        }

        otp.setConsumedAt(Instant.now());
        phoneOtpCodeRepository.save(otp);
        return user;
    }

    private String generateCode() {
        if (authProperties.isExposeTokensInResponse()) {
            return DEV_FIXED_CODE;
        }
        int value = secureRandom.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    private static SecurityActionException invalidCode() {
        return new SecurityActionException("Invalid or expired code.");
    }
}
