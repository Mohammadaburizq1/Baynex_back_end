package com.byonix.shoplink.service.productimport;

/**
 * The uploaded file as a whole cannot be imported (wrong format, unreadable, too large, missing
 * required columns). Answered as 400 with a merchant-readable message; rows are reported separately.
 */
public class ImportFileException extends IllegalArgumentException {
    private final String code;

    public ImportFileException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
