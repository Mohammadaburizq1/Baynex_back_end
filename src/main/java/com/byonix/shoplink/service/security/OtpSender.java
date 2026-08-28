package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.enums.OtpPurpose;

/**
 * Delivers a one-time phone verification code. {@link LoggingOtpSender} is the only
 * implementation shipped today (dev-mode, logs the code server-side) — a real SMS/WhatsApp
 * provider plugs in later as another bean, with no changes to {@link OtpService} or callers.
 */
public interface OtpSender {
    void send(String phone, String code, OtpPurpose purpose);
}
