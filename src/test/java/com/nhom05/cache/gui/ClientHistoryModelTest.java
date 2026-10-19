package com.nhom05.cache.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientHistoryModelTest {

    private ClientHistoryModel model;

    @BeforeEach
    void setUp() {
        model = new ClientHistoryModel();
    }

    @Test
    void testPutKeyAndExplorerUpdates() {
        assertEquals(0, model.keyCount());

        model.putKey("user:1", "Duy", 0, 1);
        assertEquals(1, model.keyCount());

        KeyEntry entry = model.findKey("user:1");
        assertNotNull(entry);
        assertEquals("user:1", entry.key());
        assertEquals("Duy", entry.value());
        assertEquals(0, entry.primaryIndex());
        assertEquals(1, entry.replicaIndex());

        // Cap nhat gia tri key cu
        model.putKey("user:1", "Duy Nguyen", 0, 1);
        assertEquals(1, model.keyCount());
        assertEquals("Duy Nguyen", model.findKey("user:1").value());
    }

    @Test
    void testRemoveKey() {
        model.putKey("keyA", "valA", 1, 2);
        model.putKey("keyB", "valB", 2, 0);
        assertEquals(2, model.keyCount());

        assertTrue(model.removeKey("keyA"));
        assertEquals(1, model.keyCount());
        assertNull(model.findKey("keyA"));
        assertNotNull(model.findKey("keyB"));

        assertFalse(model.removeKey("nonExistent"));
    }

    @Test
    void testUpdateValueIfPresent() {
        model.putKey("session:123", "active", 0, 1);
        model.updateValueIfPresent("session:123", "expired");
        assertEquals("expired", model.findKey("session:123").value());

        // Key chua tung co -> khong them vao
        model.updateValueIfPresent("unknown", "none");
        assertNull(model.findKey("unknown"));
    }

    @Test
    void testCommandLogs() {
        model.logCommand("PUT", "name", "Nhat", 0, false, "OK");
        model.logCommand("GET", "name", "—", 0, false, "VALUE|Nhat");
        model.logCommand("DELETE", "name", "—", 1, true, "OK");

        List<CommandRecord> logs = model.allCommandLogs();
        assertEquals(3, logs.size());

        // Dong moi nhat o dau danh sach
        CommandRecord newest = logs.get(0);
        assertEquals("DELETE", newest.command());
        assertEquals("name", newest.key());
        assertEquals(1, newest.servedBy());
        assertTrue(newest.failedOver());
        assertEquals("OK", newest.responseLine());
        assertTrue(newest.success());

        CommandRecord middle = logs.get(1);
        assertEquals("GET", middle.command());
        assertFalse(middle.failedOver());
        assertEquals("VALUE|Nhat", middle.responseLine());
    }

    @Test
    void testChangeListeners() {
        AtomicInteger counter = new AtomicInteger(0);
        model.addChangeListener(counter::incrementAndGet);

        model.putKey("k1", "v1", 0, 1);
        assertEquals(1, counter.get());

        model.logCommand("GET", "k1", "—", 0, false, "VALUE|v1");
        assertEquals(2, counter.get());

        model.removeKey("k1");
        assertEquals(3, counter.get());
    }
}
