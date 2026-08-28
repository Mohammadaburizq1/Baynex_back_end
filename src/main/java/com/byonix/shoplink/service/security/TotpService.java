package com.byonix.shoplink.service.security;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

/**
 * Verifies TOTP codes (RFC 6238) against a Base32-encoded secret.
 * 30-second step, ±1 step tolerance (accepts the previous/current/next code).
 */
@Service
public class TotpService {
    private final CodeVerifier codeVerifier;

    public TotpService() {
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(1);
        this.codeVerifier = verifier;
    }

    public boolean isValid(String base32Secret, String code) {
        if (base32Secret == null || base32Secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        return codeVerifier.isValidCode(base32Secret, code);
    }
}
