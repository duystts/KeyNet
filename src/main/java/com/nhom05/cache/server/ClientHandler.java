package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ProtocolParser.Request;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Xu ly 1 ket noi client: doc 1 dong request, thao tac len KeyValueStore,
 * tra 1 dong response roi dong ket noi (connection-per-request, muc 1 dac ta).
 */
final class ClientHandler {

    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private final KeyValueStore store;

    ClientHandler(KeyValueStore store) {
        this.store = store;
    }

    /** Xu ly 1 dong request, tra ve dong response (chua co '\n'). Tach rieng de test khong can socket. */
    String handle(String line) {
        Request req = ProtocolParser.parseRequest(line);
        try {
            return switch (req.type()) {
                case PUT -> put(req.key(), req.value());
                case GET -> get(req.key());
                case DELETE -> delete(req.key());
                case UNKNOWN -> ProtocolParser.error(ErrorCode.E001);
            };
        } catch (RuntimeException e) {
            // Loi khong luong truoc: bao E005 cho client thay vi lam chet thread
            LOGGER.log(Level.SEVERE, "Loi noi bo khi xu ly: " + line, e);
            return ProtocolParser.error(ErrorCode.E005);
        }
    }

    private String put(String key, String value) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        if (!ProtocolParser.isValueValid(value)) {
            return ProtocolParser.error(ErrorCode.E003);
        }
        store.put(key, value);
        return ProtocolParser.ok();
    }

    private String get(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        String value = store.get(key);
        return value == null ? ProtocolParser.notFound() : ProtocolParser.value(value);
    }

    private String delete(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        return store.delete(key) ? ProtocolParser.ok() : ProtocolParser.notFound();
    }
}
