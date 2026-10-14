package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
