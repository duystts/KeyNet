package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test o muc protocol: dam bao response sinh ra dung dinh dang
 * trong muc 2, 3 cua "Dac ta giao thuc - Distributed Cache System".
 */
class ClientHandlerTest {

    private KeyValueStore store;
    private ClientHandler handler;

    private ClientHandler newHandler() {
        store = new KeyValueStore(60, 100);
        // socket = null hop le o day vi handle() khong dung den socket.
        return new ClientHandler(null, store);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void put_validRequest_returnsOk() {
        handler = newHandler();
        assertEquals("OK", handler.handle("PUT|username|Duy"));
    }

    @Test
    void get_existingKey_returnsValueFormatted() {
        handler = newHandler();
        handler.handle("PUT|username|Duy");
        assertEquals("VALUE|Duy", handler.handle("GET|username"));
    }

    @Test
    void get_missingKey_returnsNotFound() {
        handler = newHandler();
        assertEquals("NOTFOUND", handler.handle("GET|khong-ton-tai"));
    }

    @Test
    void delete_existingKey_returnsOk() {
        handler = newHandler();
        handler.handle("PUT|k1|v1");
        assertEquals("OK", handler.handle("DELETE|k1"));
        assertEquals("NOTFOUND", handler.handle("GET|k1"));
    }

    @Test
    void delete_missingKey_returnsNotFound() {
        handler = newHandler();
        assertEquals("NOTFOUND", handler.handle("DELETE|khong-ton-tai"));
    }

    @Test
    void malformedCommand_returnsErrorE001() {
        handler = newHandler();
        assertEquals("ERROR|E001|Invalid command syntax", handler.handle("PUT|chi-co-key"));
        assertEquals("ERROR|E001|Invalid command syntax", handler.handle("UNKNOWNCMD|a|b"));
        assertEquals("ERROR|E001|Invalid command syntax", handler.handle(""));
    }

    @Test
    void keyTooLong_returnsErrorE002() {
        handler = newHandler();
        String longKey = "k".repeat(300);
        assertEquals("ERROR|E002|Key exceeds max length", handler.handle("PUT|" + longKey + "|v"));
    }

    @Test
    void valueTooLong_returnsErrorE003() {
        handler = newHandler();
        String longValue = "v".repeat(5000);
        assertEquals("ERROR|E003|Value exceeds max length", handler.handle("PUT|k|" + longValue));
    }

    @Test
    void commandIsCaseInsensitive() {
        handler = newHandler();
        assertEquals("OK", handler.handle("put|k1|v1"));
        assertEquals("VALUE|v1", handler.handle("get|k1"));
    }
}
