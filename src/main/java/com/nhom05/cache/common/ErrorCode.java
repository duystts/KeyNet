package com.nhom05.cache.common;

/**
 * Ma loi chuan dung chung cho toan bo he thong KeyNet.
 * Xem muc 3 trong tai lieu "Dac ta giao thuc - Distributed Cache System".
 *
 * KHONG tu them ma loi moi o day neu chua thong nhat voi ca nhom,
 * vi Client (Module 2) dua vao cac ma nay de quyet dinh co failover hay khong.
 */
public enum ErrorCode {

    E001("E001", "Invalid command syntax"),
    E002("E002", "Key exceeds max length"),
    E003("E003", "Value exceeds max length"),
    E004("E004", "Server overloaded"),
    E005("E005", "Internal server error"),
    E006("E006", "Server syncing, retry later");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
