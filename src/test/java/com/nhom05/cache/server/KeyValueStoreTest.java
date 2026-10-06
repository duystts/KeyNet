package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyValueStoreTest {

    private KeyValueStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void putThenGet_returnsSameValue() {
        store = new KeyValueStore(60, 100);
        store.put("username", "Duy");
        assertEquals("Duy", store.get("username"));
    }

    @Test
    void get_unknownKey_returnsNull() {
        store = new KeyValueStore(60, 100);
        assertNull(store.get("khong-ton-tai"));
    }

    @Test
    void delete_existingKey_returnsTrueAndRemoves() {
        store = new KeyValueStore(60, 100);
        store.put("k1", "v1");
        assertTrue(store.delete("k1"));
        assertNull(store.get("k1"));
    }

    @Test
    void delete_missingKey_returnsFalse() {
        store = new KeyValueStore(60, 100);
        assertFalse(store.delete("khong-ton-tai"));
    }

    @Test
    void put_overwritesExistingValue() {
        store = new KeyValueStore(60, 100);
        store.put("k1", "v1");
        store.put("k1", "v2");
        assertEquals("v2", store.get("k1"));
    }

    @Test
    void ttlExpiry_keyBecomesUnavailableAfterExpiry() throws InterruptedException {
        // TTL = 1 giay de test nhanh, sweeper chay moi 1 giay
        store = new KeyValueStore(1, 100);
        store.put("temp", "value");
        assertEquals("value", store.get("temp"));

        Thread.sleep(1300);
        // get() tu kiem tra het han ngay ca khi sweeper chua chay kip
        assertNull(store.get("temp"));
    }

    @Test
    void sweepExpired_removesExpiredKeysProactively() throws InterruptedException {
        store = new KeyValueStore(1, 100);
        store.put("temp", "value");

        Thread.sleep(1300);
        store.sweepExpired();

        assertEquals(0, store.size());
    }

    @Test
    void lruEviction_removesLeastRecentlyUsedWhenOverCapacity() {
        store = new KeyValueStore(60, 2);

        store.put("a", "1");
        store.put("b", "2");
        // Truy cap "a" de no thanh "moi dung gan day" hon "b"
        store.get("a");

        // Them key thu 3 -> vuot capacity=2 -> "b" (it dung nhat) bi evict
        store.put("c", "3");

        assertEquals(2, store.size());
        assertNotNull(store.get("a"));
        assertNull(store.get("b"));
        assertNotNull(store.get("c"));
    }

    @Test
    void concurrentPuts_areThreadSafe() throws InterruptedException {
        store = new KeyValueStore(60, 10_000);
        int threads = 20;
        int opsPerThread = 200;
        Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    store.put("key-" + threadId + "-" + i, "value-" + i);
                }
            });
            workers[t].start();
        }
        for (Thread w : workers) {
            w.join();
        }

        assertEquals(threads * opsPerThread, store.size());
    }

    @Test
    void localTimestampsAreStrictlyIncreasing() {
        store = new KeyValueStore(60, 100);
        long prev = 0;
        for (int i = 0; i < 1000; i++) {
            long ts = store.put("k", "v" + i);
            assertTrue(ts > prev, "timestamp phai tang nghiem ngat");
            prev = ts;
        }
    }

    @Test
    void replicatedPutIsIgnoredWhenOlderThanLocalWrite() {
        store = new KeyValueStore(60, 100);
        long ts = store.put("k", "local");
        assertFalse(store.putReplicated("k", "old", ts - 1));
        assertFalse(store.putReplicated("k", "same", ts));
        assertEquals("local", store.get("k"));
        assertTrue(store.putReplicated("k", "newer", ts + 1));
        assertEquals("newer", store.get("k"));
    }

    @Test
    void localDeleteBlocksLateReplicatedPut() {
        store = new KeyValueStore(60, 100);
        long ts = store.put("k", "v");
        store.delete("k", ts + 10);
        assertFalse(store.putReplicated("k", "stale", ts + 5));
        assertNull(store.get("k"));
    }
}
