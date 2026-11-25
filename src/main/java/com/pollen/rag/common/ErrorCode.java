package com.pollen.rag.common;

public final class ErrorCode {

    public static final int OK = 0;
    public static final int BAD_REQUEST = 40001;
    public static final int NOT_FOUND = 40401;
    public static final int DUPLICATE = 40901;
    public static final int PARSE_FAILED = 42201;
    public static final int RATE_LIMITED = 42901;
    public static final int INTERNAL = 50001;
    public static final int MODEL_UNAVAILABLE = 50301;
    public static final int VECTOR_STORE_UNAVAILABLE = 50311;

    private ErrorCode() {
    }
}
