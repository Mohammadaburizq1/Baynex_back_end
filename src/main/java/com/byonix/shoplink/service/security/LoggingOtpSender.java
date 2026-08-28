package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.enums.OtpPurpose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-only OTP delivery: logs the code instead of sending an SMS/WhatsApp message.
 * Mirrors {@link DevTokenLogger}'s gating — never enable app.auth.expose-tokens-in-response
 * in production. Wired as the default {@link OtpSender} bean by OtpSenderConfig.
 */
@Slf4j
@RequiredArgsConstructor
public class LoggingOtpSender implements OtpSender {
    private final AuthProperties authProperties;

    @Override
    public void send(String phone, String code, OtpPurpose purpose) {
        if (authProperties.isExposeTokensInResponse()) {
            log.warn("DEV ONLY phone OTP for {} ({}) — do not enable in production", phone, purpose);
            log.warn("DEV OTP code for {}: {}", phone, code);
        }
    }
}
