package com.nhom05.cache.common;

/**
 * Parse va build message theo dung cu phap trong muc 2 va 3 cua
 * "Dac ta giao thuc - Distributed Cache System".
 *
 * Format request:  PUT|<key>|<value>   GET|<key>   DELETE|<key>
 * Format response: OK   VALUE|<value>   NOTFOUND   ERROR|<ma loi>|<mo ta>
 *
 * Lop nay KHONG tu doc/ghi socket - chi lam nhiem vu parse chuoi,
 * de ClientHandler (Module 1) va cac module khac tai su dung.
 */
public final class ProtocolParser {

    public static final String DELIMITER = "\\|";
    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_VALUE_LENGTH = 4096;

    private ProtocolParser() {
    }

    public enum CommandType { PUT, GET, DELETE, UNKNOWN }

    /** Ket qua parse 1 dong request tu client. */
    public record ParsedRequest(CommandType type, String key, String value) {
    }

    /**
     * Parse 1 dong request thanh ParsedRequest.
     * Neu cu phap sai, tra ve CommandType.UNKNOWN (goi code se tra loi ERROR|E001).
     */
    public static ParsedRequest parseRequest(String line) {
        if (line == null) {
            return new ParsedRequest(CommandType.UNKNOWN, null, null);
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return new ParsedRequest(CommandType.UNKNOWN, null, null);
        }

        String[] parts = trimmed.split(DELIMITER, -1);
        String command = parts[0].toUpperCase();

        switch (command) {
            case "PUT" -> {
                if (parts.length != 3) {
                    return new ParsedRequest(CommandType.UNKNOWN, null, null);
                }
                return new ParsedRequest(CommandType.PUT, parts[1], parts[2]);
            }
            case "GET" -> {
                if (parts.length != 2) {
                    return new ParsedRequest(CommandType.UNKNOWN, null, null);
                }
                return new ParsedRequest(CommandType.GET, parts[1], null);
            }
            case "DELETE" -> {
                if (parts.length != 2) {
                    return new ParsedRequest(CommandType.UNKNOWN, null, null);
                }
                return new ParsedRequest(CommandType.DELETE, parts[1], null);
            }
            default -> {
                return new ParsedRequest(CommandType.UNKNOWN, null, null);
            }
        }
    }

    /** Ket qua parse 1 dong REPLICATE (muc 5 dac ta). value = null voi lenh DELETE. */
    public record ParsedReplicate(boolean isPut, String key, String value, long timestamp) {
    }

    /**
     * Parse lenh dong bo giua server:
     *   REPLICATE|PUT|key|value|timestamp   REPLICATE|DELETE|key|timestamp
     * Tra ve null neu sai cu phap, thieu/thua truong, key/value khong hop le
     * hoac timestamp khong phai so (caller tra ERROR|E001).
     */
    public static ParsedReplicate parseReplicate(String line) {
        if (line == null) {
            return null;
        }
        String[] p = line.strip().split(DELIMITER, -1);
        if (p.length < 4 || !"REPLICATE".equalsIgnoreCase(p[0])) {
            return null;
        }
        try {
            if ("PUT".equalsIgnoreCase(p[1]) && p.length == 5) {
                if (!isKeyValid(p[2]) || !isValueValid(p[3])) {
                    return null;
                }
                return new ParsedReplicate(true, p[2], p[3], Long.parseLong(p[4]));
            }
            if ("DELETE".equalsIgnoreCase(p[1]) && p.length == 4) {
                if (!isKeyValid(p[2])) {
                    return null;
                }
                return new ParsedReplicate(false, p[2], null, Long.parseLong(p[3]));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    public static boolean isKeyValid(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_LENGTH
                && !key.contains("|") && !key.contains("\n");
    }

    public static boolean isValueValid(String value) {
        return value != null && value.length() <= MAX_VALUE_LENGTH
                && !value.contains("|") && !value.contains("\n");
    }

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
        return "ERROR|" + code.code() + "|" + code.defaultMessage();
    }

    public static String error(ErrorCode code, String customMessage) {
        return "ERROR|" + code.code() + "|" + customMessage;
    }
}
