package com.nhom05.cache.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Kho Key-Value trong RAM cua 1 Cache Server (Module 1).
 *
 * - Luu bang ConcurrentHashMap (muc 8 dac ta: thao tac doc/ghi kho chinh phai thread-safe).
 * - TTL: moi key song ttlSeconds ke tu lan ghi; GET key het han tra null, thread nen
 *   quet xoa key het han moi giay de giai phong RAM.
 * - LRU: so key vuot maxEntries thi xoa key lau khong duoc truy cap nhat.
 *
 * Lop nay khong biet gi ve socket / giao thuc, de test doc lap.
 */
public final class KeyValueStore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(KeyValueStore.class.getName());

    private final ConcurrentHashMap<String, CacheEntry> map = new ConcurrentHashMap<>();
    /** Bo dem thu tu truy cap cho LRU (tang dan, khong trung nhu dung thoi gian). */
    private final AtomicLong accessCounter = new AtomicLong();
    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier clock;
    private final ScheduledExecutorService sweeper;

    public KeyValueStore(long ttlSeconds, int maxEntries) {
        this(ttlSeconds, maxEntries, System::currentTimeMillis, true);
    }

    /** Cho test truyen dong ho gia de kiem tra TTL ma khong phai sleep. */
    KeyValueStore(long ttlSeconds, int maxEntries, LongSupplier clock, boolean startSweeper) {
        if (ttlSeconds <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("ttlSeconds va maxEntries phai > 0");
        }
        this.ttlMillis = TimeUnit.SECONDS.toMillis(ttlSeconds);
        this.maxEntries = maxEntries;
        this.clock = clock;
        if (startSweeper) {
            this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kv-ttl-sweeper");
                t.setDaemon(true);
                return t;
            });
            this.sweeper.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
        } else {
            this.sweeper = null;
        }
    }

    /** Ghi hoac ghi de key, TTL tinh lai tu bay gio. */
    public void put(String key, String value) {
        map.put(key, new CacheEntry(value, clock.getAsLong() + ttlMillis, accessCounter.incrementAndGet()));
        evictIfNeeded();
    }

    /** Doc gia tri; null neu khong co hoac da het han. */
    public String get(String key) {
        CacheEntry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            map.remove(key, entry); // chi xoa dung ban het han, khong xoa ban vua duoc ghi moi
            return null;
        }
        entry.touch(accessCounter.incrementAndGet());
        return entry.value();
    }

    /** Xoa key; true neu key ton tai (va chua het han) truoc khi xoa. */
    public boolean delete(String key) {
        CacheEntry removed = map.remove(key);
        return removed != null && !removed.isExpired(clock.getAsLong());
    }

    /** So key dang luu (co the gom ca key het han ma sweeper chua kip don). */
    public int size() {
        return map.size();
    }

    /** Xoa cac key het han. Sweeper goi moi giay; test goi truc tiep. */
    int sweepExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> e : map.entrySet()) {
            if (e.getValue().isExpired(now) && map.remove(e.getKey(), e.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            int n = removed;
            LOGGER.fine(() -> "TTL sweep xoa " + n + " key");
        }
        return removed;
    }

    /**
     * LRU: con vuot maxEntries thi xoa key co lastAccess nho nhat.
     * Xoa co dieu kien remove(key, entry): neu trong luc quet co thread khac vua ghi lai
     * key do thi entry da doi, lenh xoa khong co tac dung -> khong xoa nham ban moi.
     */
    private void evictIfNeeded() {
        while (map.size() > maxEntries) {
            Map.Entry<String, CacheEntry> oldest = null;
            for (Map.Entry<String, CacheEntry> e : map.entrySet()) {
                if (oldest == null || e.getValue().lastAccess() < oldest.getValue().lastAccess()) {
                    oldest = e;
                }
            }
            if (oldest == null) {
                return;
            }
            if (map.remove(oldest.getKey(), oldest.getValue())) {
                String key = oldest.getKey();
                LOGGER.fine(() -> "LRU xoa key: " + key);
            }
        }
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }
}
