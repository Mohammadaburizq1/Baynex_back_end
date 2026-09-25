package com.byonix.shoplink.common;

/**
 * A request that is well-formed and authorized but clashes with existing data the caller can fix
 * (a duplicate SKU or slug within their own store). Mapped to 409 with the message shown to the
 * merchant as-is — unlike DataIntegrityViolationException, which only ever says "conflicts with
 * existing data" because the raw constraint failure isn't safe or useful to surface.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
