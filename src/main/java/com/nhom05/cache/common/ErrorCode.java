package com.nhom05.cache.common;

/**
 * Ma loi chuan dung chung cho toan he thong (muc 3 dac ta giao thuc).
 *
 * Client dua vao ma loi de quyet dinh co chuyen sang server du phong hay khong:
 * E004 va E006 nen failover, cac ma con lai la loi du lieu, doi server cung vay.
 * Khong tu them ma moi neu chua thong nhat voi ca nhom.
 */
public enum ErrorCode {

    E001("Invalid command syntax"),
    E002("Key exceeds max length"),
    E003("Value exceeds max length"),
    E004("Server overloaded"),
    E005("Internal server error"),
    E006("Server syncing, retry later");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** Ma loi dang chuoi, vd "E001" (chinh la ten hang so enum). */
    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** Loi nay co nen chuyen sang server du phong khong (muc 3 dac ta). */
    public boolean shouldFailover() {
        return this == E004 || this == E006;
    }
}
