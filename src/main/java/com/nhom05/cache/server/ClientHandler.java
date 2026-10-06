package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ProtocolParser.ParsedRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
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
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {

            String line = in.readLine();
            String response = handle(line);
            out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Loi xu ly ket noi client: " + e.getMessage(), e);
        }
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
