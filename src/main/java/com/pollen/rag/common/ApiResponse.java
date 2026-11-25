package com.pollen.rag.common;

import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, nextRequestId());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, nextRequestId());
    }

    private static String nextRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
