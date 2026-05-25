package com.byonix.shoplink.common;

import java.util.Map;

public record ApiResponse<T>(boolean success, String message, T data, Map<String, String> errors) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data, null);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, "Created", data, null);
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    public static ApiResponse<Void> validation(Map<String, String> errors) {
        return new ApiResponse<>(false, "Validation failed", null, errors);
    }
}
