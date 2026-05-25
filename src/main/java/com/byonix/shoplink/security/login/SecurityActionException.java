package com.byonix.shoplink.security.login;

/**
 * Generic security-related failures (reset/verify tokens) without leaking details.
 */
public class SecurityActionException extends RuntimeException {
    public static final String GENERIC_TOKEN_MESSAGE = "Invalid or expired link";
    public static final String GENERIC_RESET_MESSAGE =
            "If an account exists for this email, password reset instructions have been sent.";
    public static final String GENERIC_VERIFY_RESEND_MESSAGE =
            "If this email is registered and not yet verified, we sent a verification link.";

    public SecurityActionException(String message) {
        super(message);
    }

    public static SecurityActionException invalidToken() {
        return new SecurityActionException(GENERIC_TOKEN_MESSAGE);
    }
}
