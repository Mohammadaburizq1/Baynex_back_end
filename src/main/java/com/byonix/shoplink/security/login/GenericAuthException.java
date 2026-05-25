package com.byonix.shoplink.security.login;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * Always surfaces the same client message to prevent account enumeration.
 */
public class GenericAuthException extends BadCredentialsException {
    public static final String MESSAGE = "Invalid credentials";

    public GenericAuthException() {
        super(MESSAGE);
    }
}
