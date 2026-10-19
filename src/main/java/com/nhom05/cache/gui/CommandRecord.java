package com.nhom05.cache.gui;

/**
 * Ban ghi nhat ky mot thao tac lenh duoc GUI Client thuc hien.
 */
public record CommandRecord(
        String timestamp,
        String command,
        String key,
        String value,
        int servedBy,
        boolean failedOver,
        String responseLine,
        boolean success
) {
}
