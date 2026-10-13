package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class KeyValueStoreTest {

    /** Dong ho gia: test TTL bang cach "tua" thoi gian, khong can sleep. */
    private final AtomicLong now = new AtomicLong(1_000_000);
    private KeyValueStore store;

    private KeyValueStore storeWithFakeClock(long ttlSeconds, int maxEntries) {
        store = new KeyValueStore(ttlSeconds, maxEntries, now::get, false);
        return store;
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    // ------------------------------------------------------------------ co ban

    @Test
    void putThenGet() {
        storeWithFakeClock(60, 100).put("username", "Duy");
        assertEquals("Duy", store.get("username"));
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertNull(storeWithFakeClock(60, 100).get("khong-co"));
    }

    @Test
    void putOverwritesOldValue() {
        storeWithFakeClock(60, 100).put("k", "v1");
        store.put("k", "v2");
        assertEquals("v2", store.get("k"));
        assertEquals(1, store.size());
    }

    @Test
    void deleteExistingAndMissingKey() {
        storeWithFakeClock(60, 100).put("k", "v");
        assertTrue(store.delete("k"));
        assertNull(store.get("k"));
        assertFalse(store.delete("k"), "xoa lan 2 -> khong con key");
    }

    @Test
    void keepsVietnameseAndEmptyValue() {
        storeWithFakeClock(60, 100).put("ten", "Nguyễn Tiến Đức");
        store.put("rong", "");
        assertEquals("Nguyễn Tiến Đức", store.get("ten"));
        assertEquals("", store.get("rong"));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new KeyValueStore(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new KeyValueStore(10, 0));
    }

    // ------------------------------------------------------------------ TTL

    @Test
    void keyExpiresAfterTtl() {
        storeWithFakeClock(60, 100).put("k", "v");
        now.addAndGet(59_999);
        assertEquals("v", store.get("k"), "chua het 60 giay");
        now.addAndGet(1);
        assertNull(store.get("k"), "du 60 giay -> het han");
        assertEquals(0, store.size(), "GET key het han thi xoa luon");
    }

    @Test
    void rewritingKeyResetsTtl() {
        storeWithFakeClock(60, 100).put("k", "v1");
        now.addAndGet(50_000);
        store.put("k", "v2");
        now.addAndGet(50_000);
        assertEquals("v2", store.get("k"), "TTL tinh lai tu lan ghi thu 2");
    }

    @Test
    void deletingExpiredKeyReportsNotFound() {
        storeWithFakeClock(60, 100).put("k", "v");
        now.addAndGet(60_000);
        assertFalse(store.delete("k"));
    }

    @Test
    void sweeperRemovesOnlyExpiredKeys() {
        storeWithFakeClock(60, 100).put("cu", "1");
        now.addAndGet(30_000);
        store.put("moi", "2");
        now.addAndGet(30_000); // "cu" du 60s, "moi" moi 30s
        assertEquals(1, store.sweepExpired());
        assertEquals(1, store.size());
        assertEquals("2", store.get("moi"));
    }

    @Test
    void backgroundSweeperRunsWithRealClock() throws InterruptedException {
        store = new KeyValueStore(1, 100);
        store.put("k", "v");
        Thread.sleep(2_500); // TTL 1s + sweeper chay moi 1s
        assertEquals(0, store.size(), "thread nen phai tu xoa key het han");
    }

    // ------------------------------------------------------------------ LRU

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        storeWithFakeClock(60, 3).put("a", "1");
        store.put("b", "2");
        store.put("c", "3");
        store.get("a");          // "a" vua duoc dung -> "b" thanh lau nhat
        store.put("d", "4");     // vuot 3 key -> xoa "b"
        assertEquals(3, store.size());
        assertNull(store.get("b"));
        assertEquals("1", store.get("a"));
        assertEquals("3", store.get("c"));
        assertEquals("4", store.get("d"));
    }

    @Test
    void overwritingKeyDoesNotTriggerEviction() {
        storeWithFakeClock(60, 2).put("a", "1");
        store.put("b", "2");
        store.put("a", "1b"); // ghi de, so key van la 2
        assertEquals(2, store.size());
        assertEquals("2", store.get("b"));
    }

    // ------------------------------------------------------------------ da luong

    @Test
    void concurrentWritesFromManyThreads() throws InterruptedException {
        store = new KeyValueStore(60, 100_000);
        int threads = 16, perThread = 1_000;
        List<Thread> list = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    store.put("t" + id + "_" + i, "v" + i);
                }
            });
            list.add(th);
            th.start();
        }
        for (Thread th : list) {
            th.join();
        }
        assertEquals(threads * perThread, store.size());
        assertEquals("v999", store.get("t15_999"));
    }

    @Test
    void sizeNeverExceedsLimitUnderConcurrentWrites() throws InterruptedException {
        store = new KeyValueStore(60, 50);
        List<Thread> list = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 2_000; i++) {
                    store.put("t" + id + "_" + i, "v");
                }
            });
            list.add(th);
            th.start();
        }
        for (Thread th : list) {
            th.join();
        }
        assertTrue(store.size() <= 50, "size = " + store.size());
    }
}
