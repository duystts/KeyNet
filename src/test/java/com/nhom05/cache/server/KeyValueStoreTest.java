package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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

    // ------------------------------------------------------------------ xung dot phan tan (LWW + Tombstone)

    @Test
    void nextTimestampTangNghiemNgat() {
        store = storeWithFakeClock(60, 100);
        long t1 = store.nextTimestamp();
        long t2 = store.nextTimestamp();
        long t3 = store.nextTimestamp();
        assertTrue(t1 < t2, "t1 phai nho hon t2");
        assertTrue(t2 < t3, "t2 phai nho hon t3");

        // Khi dong ho nhay vot len, nextTimestamp phai nhay theo dong ho
        now.set(5_000_000);
        long t4 = store.nextTimestamp();
        assertTrue(t4 >= 5_000_000, "t4 phai >= thoi diem moi");
        long t5 = store.nextTimestamp();
        assertTrue(t4 < t5, "t4 phai nho hon t5");
    }

    @Test
    void nextTimestampAnToanDaLuong() throws InterruptedException {
        store = new KeyValueStore(60, 1000);
        int threads = 10;
        int countPerThread = 500;
        Set<Long> generated = ConcurrentHashMap.newKeySet();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < countPerThread; j++) {
                        generated.add(store.nextTimestamp());
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(threads * countPerThread, generated.size(), "Tat ca cac timestamp sinh ra phai duy nhat, khong bi trung lap");
    }

    @Test
    void updateLastTimestampTuDongCapNhatVaKeoBoDemLen() {
        store = storeWithFakeClock(60, 100);
        assertEquals(0, store.getLastTimestamp());

        // Nhan timestamp tu replication lon hon dong ho hien tai (1_000_000)
        store.updateLastTimestamp(2_000_000);
        assertEquals(2_000_000, store.getLastTimestamp());

        // Lan ghi ke tiep phai co timestamp lon hon 2_000_000 mac du dong ho may van o 1_000_000
        long next = store.nextTimestamp();
        assertTrue(next > 2_000_000, "nextTimestamp phai > 2_000_000: " + next);
    }

    @Test
    void putTraVeTimestampVaGhiVaoBanGhi() {
        store = storeWithFakeClock(60, 100);
        long ts1 = store.put("k", "v1");
        assertTrue(ts1 >= 1_000_000);
        assertEquals(ts1, store.getTimestamp("k"));

        long ts2 = store.put("k", "v2");
        assertTrue(ts2 > ts1);
        assertEquals(ts2, store.getTimestamp("k"));
        assertEquals("v2", store.get("k"));
    }

    @Test
    void putReplicatedSaiThuTuTimestampNhoHonBiBoQua() {
        store = storeWithFakeClock(60, 100);

        // Ban tin timestamp 3000 den truoc
        assertTrue(store.putReplicated("k", "v3000", 3000));
        assertEquals("v3000", store.get("k"));
        assertEquals(3000, store.getTimestamp("k"));

        // Ban tin timestamp 2000 den sau bi bo qua (LWW)
        assertFalse(store.putReplicated("k", "v2000", 2000), "PUT voi timestamp cu hon phai bi bo qua");
        assertEquals("v3000", store.get("k"), "Gia tri k van phai la v3000");
        assertEquals(3000, store.getTimestamp("k"));

        // Ban tin timestamp bang 3000 cung bi bo qua (khong ghi de)
        assertFalse(store.putReplicated("k", "v3000_dup", 3000), "PUT voi timestamp bang phai bi bo qua");
        assertEquals("v3000", store.get("k"));

        // Ban tin timestamp 4000 lon hon duoc chap nhan ghi de
        assertTrue(store.putReplicated("k", "v4000", 4000));
        assertEquals("v4000", store.get("k"));
        assertEquals(4000, store.getTimestamp("k"));
    }

    @Test
    void deleteRoiPutCuHonDenTreBiChanBoiTombstone() {
        store = storeWithFakeClock(60, 100);

        // Ghi ban ghi luc 1000
        assertTrue(store.putReplicated("k", "v1", 1000));
        assertEquals("v1", store.get("k"));

        // Xoa tai timestamp 3000 (luu tombstone tai 3000)
        assertTrue(store.deleteReplicated("k", 3000));
        assertNull(store.get("k"), "Key phai bi xoa");

        // Ban tin PUT cu hon (timestamp 2000) den tre -> bi tombstone chan
        assertFalse(store.putReplicated("k", "v_delayed", 2000), "PUT cu hon tombstone phai bi chan");
        assertNull(store.get("k"), "Key khong duoc song lai boi PUT cu");

        // Ban tin PUT co timestamp bang 3000 cung bi chan
        assertFalse(store.putReplicated("k", "v_same", 3000));
        assertNull(store.get("k"));

        // Ban tin PUT moi hon han (timestamp 4000) duoc chap nhan va xoa tombstone
        assertTrue(store.putReplicated("k", "v_new", 4000));
        assertEquals("v_new", store.get("k"));
        assertEquals(4000, store.getTimestamp("k"));
    }

    @Test
    void deleteCuHonBanDangCoBiBoQua() {
        store = storeWithFakeClock(60, 100);

        // Ban ghi moi hon co timestamp 3000
        assertTrue(store.putReplicated("k", "v3000", 3000));
        assertEquals("v3000", store.get("k"));

        // Ban tin DELETE cu hon (timestamp 2000) den sau bi bo qua
        assertFalse(store.deleteReplicated("k", 2000), "DELETE cu hon phai bi bo qua");
        assertEquals("v3000", store.get("k"), "Key khong duoc bi xoa boi DELETE cu");
        assertEquals(3000, store.getTimestamp("k"));

        // Ban tin DELETE moi hon (timestamp 3500) duoc chap nhan xoa
        assertTrue(store.deleteReplicated("k", 3500));
        assertNull(store.get("k"));

        // Ban tin DELETE cu hon tombstone (timestamp 3000 <= 3500) bi bo qua
        assertFalse(store.deleteReplicated("k", 3000));
    }

    @Test
    void deleteCucBoTaoTombstoneVaChanPutCu() {
        store = storeWithFakeClock(60, 100);

        long tsPut = store.put("k", "v1");
        assertTrue(store.delete("k"));
        assertNull(store.get("k"));

        // PUT replicated voi timestamp cu hon hoac bang lan put truoc do bi chan
        assertFalse(store.putReplicated("k", "v_old", tsPut));
        assertNull(store.get("k"));
    }

    @Test
    void tombstoneTuDongDonDepTheoChuKyTTL() {
        store = storeWithFakeClock(60, 100);

        // Xoa key de tao tombstone
        store.deleteReplicated("k", 2000);
        assertEquals(1, store.tombstoneCount(), "Phai co 1 tombstone");

        // Chua het TTL (59s) -> tombstone van con va van chan PUT cu
        now.addAndGet(59_000);
        assertFalse(store.putReplicated("k", "v_old", 1500));

        // Du TTL 60s -> sweeper don dep tombstone
        now.addAndGet(1_000);
        store.sweepExpired();
        assertEquals(0, store.tombstoneCount(), "Tombstone phai bi don dep sau khi het han TTL");

        // Sau khi tombstone het han, key co the duoc ghi lai binh thuong
        assertTrue(store.putReplicated("k", "v_new", 2500));
        assertEquals("v_new", store.get("k"));
    }

    @Test
    void snapshotChuaCacKeyConHan() {
        store = storeWithFakeClock(60, 100);
        store.putReplicated("k1", "v1", 1000);
        store.putReplicated("k2", "v2", 2000);

        var list = store.snapshot();
        assertEquals(2, list.size());

        // Lam k1 het han
        now.addAndGet(60_000);
        store.putReplicated("k3", "v3", 3000); // k3 vua ghi, k1 va k2 da het han

        var list2 = store.snapshot();
        assertEquals(1, list2.size());
        assertEquals("k3", list2.get(0).key());
        assertEquals("v3", list2.get(0).value());
        assertEquals(3000, list2.get(0).timestamp());
    }

    @Test
    void concurrencyAtomicLWWVaTombstone() throws InterruptedException {
        store = new KeyValueStore(60, 1000);
        int threads = 10;
        int operationsPerThread = 200;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // Chay cac thao tac putReplicated va deleteReplicated dong thoi voi timestamp duy nhat
        for (int i = 0; i < threads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        long ts = (long) (threadId * operationsPerThread + j + 1);
                        if (j % 2 == 0) {
                            store.putReplicated("concurrent_key", "val_" + ts, ts);
                        } else {
                            store.deleteReplicated("concurrent_key", ts);
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // Timestamp lon nhat tong cong la maxTs
        long maxTs = (long) (threads * operationsPerThread);
        // Neu maxTs la so le -> thao tac cuoi cung theo timestamp la delete
        // Neu maxTs la so chan -> thao tac cuoi cung theo timestamp la put
        boolean lastOpWasDelete = (maxTs % 2 == 0);

        if (lastOpWasDelete) {
            assertNull(store.get("concurrent_key"), "Thao tac co timestamp lon nhat la delete thi ket qua phai null");
        } else {
            assertEquals("val_" + maxTs, store.get("concurrent_key"), "Thao tac co timestamp lon nhat la put thi ket qua phai khop");
            assertEquals(maxTs, store.getTimestamp("concurrent_key"));
        }
    }
}
