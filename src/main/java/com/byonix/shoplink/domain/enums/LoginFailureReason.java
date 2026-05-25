package com.byonix.shoplink.domain.enums;

public enum LoginFailureReason {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    MFA_REQUIRED,
    MFA_INVALID,
    FORCE_PASSWORD_RESET,
    IP_BLOCKED,
    HIGH_RISK_BLOCKED,
    EMAIL_NOT_VERIFIED,
    RATE_LIMITED
}
