package com.byonix.shoplink.security.login;

/**
 * Generic security-related failures (reset/verify tokens) without leaking details.
 */
public class SecurityActionException extends RuntimeException {
    public static final String GENERIC_TOKEN_MESSAGE = "Invalid or expired link";
    public static final String GENERIC_RESET_MESSAGE =
            "Request received. If eligible, check your inbox for password reset instructions.";
    public static final String GENERIC_VERIFY_RESEND_MESSAGE =
            "Request received. If eligible, check your inbox for a verification link.";

    public SecurityActionException(String message) {
        super(message);
    }

    public static SecurityActionException invalidToken() {
        return new SecurityActionException(GENERIC_TOKEN_MESSAGE);
    }
}
