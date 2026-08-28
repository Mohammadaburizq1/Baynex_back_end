package com.byonix.shoplink.util;

public final class PhoneNormalizer {
    private PhoneNormalizer() {}

    public static String digitsOnly(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9]", "");
    }

    /** `+` + digits for API/storage when at least 7 digits. */
    public static String toApiForm(String raw) {
        String d = digitsOnly(raw);
        if (d.length() < 7) return null;
        return "+" + d;
    }
}
