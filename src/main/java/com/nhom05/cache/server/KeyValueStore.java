package com.nhom05.cache.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Key-Value Store trong RAM cho 1 Cache Server.
 *
 * - Luu tru chinh bang ConcurrentHashMap (bat buoc theo muc 8 cua dac ta giao thuc:
 *   "moi thao tac doc/ghi tren kho du lieu chinh phai dung ConcurrentHashMap").
 * - TTL: moi key co thoi gian song rieng (mac dinh = ttlSeconds cau hinh),
 *   mot thread nen (ScheduledExecutorService) quet va xoa key het han dinh ky.
 * - LRU eviction: khi so luong key vuot maxEntries, xoa key co lastAccessMillis
 *   nho nhat (it dung nhat) de nhuong cho ghi moi.
 *
 * Lop nay KHONG biet gi ve socket/giao thuc mang - chi la engine luu tru thuan tuy,
 * de co the unit test doc lap (xem KeyValueStoreTest).
 */
public final class KeyValueStore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(KeyValueStore.class.getName());

    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final ScheduledExecutorService ttlSweeper;

    public KeyValueStore(long ttlSeconds, int maxEntries) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.maxEntries = maxEntries;
        this.ttlSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-sweeper");
            t.setDaemon(true);
            return t;
        });
        // Quet moi 1 giay de xoa key het han (xem muc 8 - luu y trien khai)
        this.ttlSweeper.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    /** Ghi hoac ghi de 1 key, dat lai TTL ke tu bay gio. */
    public void put(String key, String value) {
        long expireAt = System.currentTimeMillis() + ttlMillis;
        store.put(key, new CacheEntry(value, expireAt));
        evictIfNeeded();
    }

    /** Doc gia tri; tra null neu khong co hoac da het han. */
    public String get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            store.remove(key, entry);
            return null;
        }
        entry.touch();
        return entry.value();
    }

    /** Xoa 1 key; tra true neu key co ton tai truoc do. */
    public boolean delete(String key) {
        return store.remove(key) != null;
    }

    public int size() {
        return store.size();
    }

    /** Xoa cac key da het han TTL. Chay dinh ky boi ttlSweeper, co the goi thu cong khi test. */
    void sweepExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> e : store.entrySet()) {
            if (e.getValue().isExpired(now)) {
                if (store.remove(e.getKey(), e.getValue())) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            int removedCount = removed;
            LOGGER.fine(() -> "TTL sweep removed " + removedCount + " key(s)");
        }
    }

    /** LRU eviction: neu vuot maxEntries, xoa key it dung nhat gan day nhat. */
    private void evictIfNeeded() {
        while (store.size() > maxEntries) {
            String oldestKey = null;
            long oldestAccess = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> e : store.entrySet()) {
                long access = e.getValue().lastAccessNanos();
                if (access < oldestAccess) {
                    oldestAccess = access;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            store.remove(oldestKey);
            String evictedKey = oldestKey;
            LOGGER.fine(() -> "LRU evicted key: " + evictedKey);
        }
    }

    @Override
    public void close() {
        ttlSweeper.shutdownNow();
    }
}
