package com.byonix.shoplink.service;

import org.springframework.http.HttpStatus;

/**
 * An offline POS upload the server will never accept as sent (unknown catalog version, prices that do
 * not match the device's catalog, an operation id reused for a different sale…). The POS marks the
 * operation as a permanent failure instead of retrying it; the sale stays on the device for review.
 * The code is machine-readable ({@code POS_SYNC_<code>}); the message is safe to show a cashier.
 */
public class PosSyncRejectedException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public PosSyncRejectedException(String code, String message) {
        this(code, message, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    public PosSyncRejectedException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return "POS_SYNC_" + code;
    }

    public HttpStatus status() {
        return status;
    }
}
