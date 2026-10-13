package com.nhom05.cache.common;

/**
 * Parse va tao message theo muc 2, 3 dac ta giao thuc.
 *
 * Request:  PUT|key|value   GET|key   DELETE|key
 * Response: OK   VALUE|value   NOTFOUND   ERROR|ma loi|mo ta
 *
 * Lop nay chi xu ly chuoi, khong doc/ghi socket, de Server, Client va cac module
 * khac dung chung va test doc lap.
 */
public final class ProtocolParser {

    /** Dau phan cach truong (dang regex de dung voi String.split). */
    public static final String DELIMITER = "\\|";
    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_VALUE_LENGTH = 4096;

    private ProtocolParser() {
    }

    public enum CommandType { PUT, GET, DELETE, UNKNOWN }

    /** Ket qua parse 1 dong request. value chi co voi PUT. */
    public record Request(CommandType type, String key, String value) {

        static Request unknown() {
            return new Request(CommandType.UNKNOWN, null, null);
        }
    }

    /**
     * Parse 1 dong request. Ten lenh khong phan biet hoa/thuong (muc 2 dac ta).
     * Sai cu phap (thieu/thua truong, lenh la) -> CommandType.UNKNOWN, noi goi tra ERROR|E001.
     * Do dai key/value KHONG kiem tra o day: noi goi dung isKeyValid / isValueValid
     * de tra dung ma E002 / E003.
     */
    public static Request parseRequest(String line) {
        if (line == null || line.isBlank()) {
            return Request.unknown();
        }
        // limit -1: giu truong rong o cuoi, vd "PUT|k|" la value rong (hop le), "GET|k|" la thua truong
        String[] parts = line.strip().split(DELIMITER, -1);
        String command = parts[0].toUpperCase();
        return switch (command) {
            case "PUT" -> parts.length == 3 ? new Request(CommandType.PUT, parts[1], parts[2]) : Request.unknown();
            case "GET" -> parts.length == 2 ? new Request(CommandType.GET, parts[1], null) : Request.unknown();
            case "DELETE" -> parts.length == 2 ? new Request(CommandType.DELETE, parts[1], null) : Request.unknown();
            default -> Request.unknown();
        };
    }

    /** Key hop le: khong rong, toi da 256 ky tu, khong chua | hoac xuong dong. */
    public static boolean isKeyValid(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_LENGTH && hasNoSeparator(key);
    }

    /** Value hop le: toi da 4096 ky tu, khong chua | hoac xuong dong (duoc phep rong). */
    public static boolean isValueValid(String value) {
        return value != null && value.length() <= MAX_VALUE_LENGTH && hasNoSeparator(value);
    }

    private static boolean hasNoSeparator(String s) {
        return s.indexOf('|') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0;
    }

    // ------------------------------------------------------------------ build request (cho Client)

    public static String put(String key, String value) {
        return "PUT|" + key + "|" + value;
    }

    public static String get(String key) {
        return "GET|" + key;
    }

    public static String delete(String key) {
        return "DELETE|" + key;
    }

    // ------------------------------------------------------------------ build response (cho Server)

    public static String ok() {
        return "OK";
    }

    public static String value(String value) {
        return "VALUE|" + value;
    }

    public static String notFound() {
        return "NOTFOUND";
    }

    public static String error(ErrorCode code) {
        return error(code, code.defaultMessage());
    }

    public static String error(ErrorCode code, String message) {
        return "ERROR|" + code.code() + "|" + message;
    }

    /**
     * Lay ma loi tu 1 response dang ERROR|Exxx|mo ta. Tra null neu khong phai response loi
     * hoac ma loi khong nam trong bang chuan.
     */
    public static ErrorCode errorCodeOf(String response) {
        if (response == null || !response.startsWith("ERROR|")) {
            return null;
        }
        String[] parts = response.split(DELIMITER, 3);
        if (parts.length < 2) {
            return null;
        }
        try {
            return ErrorCode.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
