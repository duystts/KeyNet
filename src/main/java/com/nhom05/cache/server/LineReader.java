package com.nhom05.cache.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;

/**
 * Doc 1 dong request co gioi han do dai. BufferedReader.readLine() doc vo han: client gui
 * 1 dong cuc dai khong co '\n' se lam server het bo nho.
 */
final class LineReader {

    record Result(String text, boolean tooLong) {
    }

    private LineReader() {
    }

    /**
     * Doc toi '\n' hoac het du lieu. text = null neu client dong ket noi ma khong gui gi;
     * tooLong = true neu vuot maxChars (khi do text = null).
     */
    static Result read(BufferedReader in, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return new Result(sb.toString(), false);
            }
            if (sb.length() >= maxChars) {
                return new Result(null, true);
            }
            sb.append((char) c);
        }
        return new Result(sb.length() == 0 ? null : sb.toString(), false);
    }

    /**
     * Doc bo phan con lai cua dong qua dai (toi da ~1MB / 500ms) truoc khi dong socket:
     * dong khi con du lieu chua doc se gui RST, client (nhat la tren Windows) co the mat
     * luon dong loi vua gui.
     */
    static void drain(Socket socket, BufferedReader in) {
        try {
            socket.setSoTimeout(500);
            char[] buf = new char[8192];
            int total = 0;
            int n;
            while (total < 1_000_000 && (n = in.read(buf)) != -1) {
                total += n;
            }
        } catch (IOException ignored) {
            // het thoi gian hoac client da dong - khong sao
        }
    }
}
