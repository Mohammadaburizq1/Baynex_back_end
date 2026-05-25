package com.byonix.shoplink.security.request;

import jakarta.servlet.http.HttpServletRequest;

public record ClientRequestContext(
        String ipAddress,
        String userAgent,
        String deviceFingerprint,
        String country,
        String city) {

    public static final String GENERIC_AUTH_ERROR = "Invalid credentials";

    public static ClientRequestContext from(HttpServletRequest request) {
        String ip = clientIp(request);
        String ua = truncate(request.getHeader("User-Agent"), 512);
        // TODO: GeoIP lookup (country/city) via MaxMind or cloud provider
        // TODO: Validate device fingerprint from trusted client SDK
        String fingerprint = truncate(request.getHeader("X-Device-Fingerprint"), 255);
        return new ClientRequestContext(ip, ua, blankToNull(fingerprint), null, null);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
