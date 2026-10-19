package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Kiem tra response dung dinh dang muc 3 dac ta, khong can mo socket. */
class ClientHandlerTest {

    private KeyValueStore store;
    private ClientHandler handler;

    @BeforeEach
    void setUp() {
        store = new KeyValueStore(60, 100);
        handler = new ClientHandler(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void putGetDelete() {
        assertEquals("OK", handler.handle("PUT|username|Duy"));
        assertEquals("VALUE|Duy", handler.handle("GET|username"));
        assertEquals("OK", handler.handle("DELETE|username"));
        assertEquals("NOTFOUND", handler.handle("GET|username"));
        assertEquals("NOTFOUND", handler.handle("DELETE|username"));
    }

    @Test
    void commandIsCaseInsensitive() {
        assertEquals("OK", handler.handle("put|k|v"));
        assertEquals("VALUE|v", handler.handle("get|k"));
    }

    @Test
    void vietnameseValue() {
        assertEquals("OK", handler.handle("PUT|ten|Nguyễn Văn Nhật"));
        assertEquals("VALUE|Nguyễn Văn Nhật", handler.handle("GET|ten"));
    }

    @Test
    void invalidSyntaxIsE001() {
        for (String bad : new String[]{"", "HELLO", "PUT|k", "GET", "GET|a|b", "DELETE"}) {
            assertEquals("ERROR|E001|Invalid command syntax", handler.handle(bad), bad);
        }
        assertEquals("ERROR|E001|Invalid command syntax", handler.handle(null));
    }

    @Test
    void emptyOrTooLongKeyIsE002() {
        assertEquals("ERROR|E002|Key exceeds max length", handler.handle("PUT||v"));
        assertEquals("ERROR|E002|Key exceeds max length", handler.handle("GET|" + "k".repeat(257)));
        assertEquals("ERROR|E002|Key exceeds max length", handler.handle("DELETE|"));
    }

    @Test
    void tooLongValueIsE003() {
        assertEquals("ERROR|E003|Value exceeds max length", handler.handle("PUT|k|" + "v".repeat(4097)));
        assertEquals("OK", handler.handle("PUT|k|" + "v".repeat(4096)));
    }

    @Test
    void unexpectedErrorIsE005() {
        store.close();
        ClientHandler broken = new ClientHandler(null); // gia lap loi noi bo
        assertEquals("ERROR|E005|Internal server error", broken.handle("GET|k"));
    }

    /** Ghi lai cac lenh replication de kiem tra ClientHandler goi dung luc, dung timestamp. */
    private static final class RecordingReplicator implements Replicator {
        final List<String> calls = new ArrayList<>();

        @Override
        public void replicatePut(String key, String value, long timestamp) {
            calls.add("PUT|" + key + "|" + value + "|" + timestamp);
        }

        @Override
        public void replicateDelete(String key, long timestamp) {
            calls.add("DELETE|" + key + "|" + timestamp);
        }
    }

    @Test
    void replicatesSuccessfulWritesWithStoreTimestamp() {
        RecordingReplicator rep = new RecordingReplicator();
        handler.setReplicator(rep);

        handler.handle("PUT|k|v1");
        long putTs = store.getTimestamp("k");
        handler.handle("GET|k");
        handler.handle("DELETE|k");

        assertEquals(2, rep.calls.size(), "GET khong duoc replicate");
        assertEquals("PUT|k|v1|" + putTs, rep.calls.get(0));
        long deleteTs = Long.parseLong(rep.calls.get(1).substring("DELETE|k|".length()));
        assertTrue(deleteTs > putTs, "DELETE phai mang timestamp moi hon PUT truoc do");
    }

    @Test
    void doesNotReplicateRejectedRequests() {
        RecordingReplicator rep = new RecordingReplicator();
        handler.setReplicator(rep);

        handler.handle("PUT||v");
        handler.handle("PUT|k|" + "v".repeat(4097));
        handler.handle("HELLO");

        assertTrue(rep.calls.isEmpty(), "request loi (E001/E002/E003) khong duoc gui sang server du phong");
    }

    @Test
    void replicatesDeleteEvenWhenKeyMissingLocally() {
        // Key co the chi con tren server du phong (client ghi vao do luc server nay chet)
        RecordingReplicator rep = new RecordingReplicator();
        handler.setReplicator(rep);

        assertEquals("NOTFOUND", handler.handle("DELETE|chi-co-ben-du-phong"));
        assertEquals(1, rep.calls.size());
        assertTrue(rep.calls.get(0).startsWith("DELETE|chi-co-ben-du-phong|"));
    }
}
