package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ProtocolParser.ParsedRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Xu ly 1 ket noi client: doc dung 1 dong request, parse, thao tac len
 * KeyValueStore, tra ve 1 dong response, roi dong ket noi
 * (connection-per-request - xem muc 1 cua dac ta giao thuc).
 */
final class ClientHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());
    /**
     * Ket noi mo ma khong gui request trong thoi gian nay se bi dong, de client treo
     * (vd telnet bo quen) khong giu thread cua pool mai mai.
     */
    static final int IDLE_TIMEOUT_MS = 30_000;
    /** Dong request dai nhat hop le: "PUT|" + key + "|" + value (muc 2 dac ta) + chut du phong. */
    static final int MAX_LINE_LENGTH = ProtocolParser.MAX_KEY_LENGTH + ProtocolParser.MAX_VALUE_LENGTH + 16;

    private final Socket socket;
    private final KeyValueStore store;
    private final Replicator replicator;

    ClientHandler(Socket socket, KeyValueStore store) {
        this(socket, store, Replicator.NOOP);
    }

    ClientHandler(Socket socket, KeyValueStore store, Replicator replicator) {
        this.socket = socket;
        this.store = store;
        this.replicator = replicator;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
        } catch (IOException e) {
            LOGGER.fine(() -> "Khong dat duoc timeout: " + e.getMessage());
        }
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {

            String line = readLineBounded(in, MAX_LINE_LENGTH);
            String response;
            if (tooLong) {
                response = ProtocolParser.error(ErrorCode.E003);
            } else if (line == null) {
                return; // client dong ket noi ma khong gui gi
            } else {
                response = handle(line);
            }
            out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (tooLong) {
                drainQuietly(in);
            }

        } catch (SocketTimeoutException e) {
            LOGGER.fine(() -> "Dong ket noi im lang qua " + IDLE_TIMEOUT_MS + "ms");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Loi xu ly ket noi client: " + e.getMessage(), e);
        }
    }

    private boolean tooLong;

    /**
     * Doc bo phan con lai cua dong qua dai (toi da ~1MB / 500ms) truoc khi dong socket: dong khi con
     * du lieu chua doc se gui RST, client (nhat la tren Windows) co the mat luon dong ERROR vua gui.
     */
    private void drainQuietly(BufferedReader in) {
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

    /**
     * Doc 1 dong nhung khong qua maxChars ky tu: BufferedReader.readLine() doc vo han, client gui
     * 1 dong rat dai khong co '\n' se lam server het bo nho. Tra null neu EOF truoc khi co du lieu
     * hoac dong qua dai (khi do tooLong = true).
     */
    private String readLineBounded(BufferedReader in, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }
            if (sb.length() >= maxChars) {
                tooLong = true;
                return null;
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Tach rieng de unit test khong can mo socket that. */
    String handle(String requestLine) {
        ParsedRequest req = ProtocolParser.parseRequest(requestLine);

        return switch (req.type()) {
            case PUT -> handlePut(req.key(), req.value());
            case GET -> handleGet(req.key());
            case DELETE -> handleDelete(req.key());
            case UNKNOWN -> ProtocolParser.error(ErrorCode.E001);
        };
    }

    private String handlePut(String key, String value) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        if (!ProtocolParser.isValueValid(value)) {
            return ProtocolParser.error(ErrorCode.E003);
        }
        try {
            long ts = store.put(key, value);
            replicateQuietly(() -> replicator.replicatePut(key, value, ts));
            return ProtocolParser.ok();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Loi noi bo khi PUT: " + e.getMessage(), e);
            return ProtocolParser.error(ErrorCode.E005);
        }
    }

    private String handleGet(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        try {
            String value = store.get(key);
            return value == null ? ProtocolParser.notFound() : ProtocolParser.value(value);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Loi noi bo khi GET: " + e.getMessage(), e);
            return ProtocolParser.error(ErrorCode.E005);
        }
    }

    private String handleDelete(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        try {
            long ts = store.nextTimestamp();
            boolean existed = store.delete(key, ts);
            replicateQuietly(() -> replicator.replicateDelete(key, ts));
            return existed ? ProtocolParser.ok() : ProtocolParser.notFound();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Loi noi bo khi DELETE: " + e.getMessage(), e);
            return ProtocolParser.error(ErrorCode.E005);
        }
    }

    /** Replication bat dong bo: loi o day khong bao gio duoc anh huong response tra ve client. */
    private static void replicateQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Replication that bai (bo qua): " + e.getMessage(), e);
        }
    }
}
