package com.nhom05.cache.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Key-Value Store trong RAM cho 1 Cache Server.
 *
 * - Luu tru chinh bang ConcurrentHashMap (bat buoc theo muc 8 cua dac ta giao thuc).
 * - TTL: moi key co thoi gian song rieng, thread nen quet va xoa key het han dinh ky.
 * - LRU eviction: vuot maxEntries thi xoa key it dung nhat.
 * - Timestamp + tombstone: phuc vu replication (muc 5 dac ta) - ban ghi co timestamp
 *   lon hon thang; lenh DELETE de lai "tombstone" de 1 lenh PUT cu den tre khong
 *   lam song lai key da xoa.
 *
 * Lop nay KHONG biet gi ve socket/giao thuc mang.
 */
public final class KeyValueStore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(KeyValueStore.class.getName());

    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    /** key -> timestamp cua lan xoa gan nhat; tu don sau 1 chu ky TTL. */
    private final ConcurrentHashMap<String, Long> tombstones = new ConcurrentHashMap<>();
    private final AtomicLong lastTimestamp = new AtomicLong();
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
        this.ttlSweeper.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Timestamp tang nghiem ngat cho cac thao tac ghi cuc bo (System.currentTimeMillis,
     * +1 neu trung ms): 2 lan ghi lien tiep khong bao gio trung timestamp, nen server
     * du phong luon phan xu dung thu tu.
     */
    public long nextTimestamp() {
        long now = System.currentTimeMillis();
        return lastTimestamp.updateAndGet(prev -> Math.max(now, prev + 1));
    }

    /** Ghi hoac ghi de 1 key, dat lai TTL. Tra ve timestamp cua lan ghi (de replicate). */
    public long put(String key, String value) {
        long ts = nextTimestamp();
        store.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMillis, ts));
        tombstones.remove(key);
        evictIfNeeded();
        return ts;
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
        return delete(key, nextTimestamp());
    }

    /** Nhu delete(key) nhung dung timestamp do caller cap (de replicate cung timestamp). */
    public boolean delete(String key, long timestamp) {
        tombstones.merge(key, timestamp, Math::max);
        return store.remove(key) != null;
    }

    /**
     * Ap dung 1 lenh REPLICATE|PUT nhan tu server chinh. Chi ghi neu timestamp moi hon
     * ban ghi hien co VA moi hon lan xoa gan nhat cua key.
     * Ban sao nhan TTL mac dinh tinh tu luc nhan (message dong bo khong mang TTL).
     *
     * @return true neu da ghi, false neu bi bo qua do timestamp cu
     */
    public boolean putReplicated(String key, String value, long timestamp) {
        boolean[] applied = {false};
        store.compute(key, (k, existing) -> {
            Long deletedAt = tombstones.get(k);
            if (deletedAt != null && deletedAt >= timestamp) {
                return existing;
            }
            if (existing != null && existing.timestamp() >= timestamp) {
                return existing;
            }
            applied[0] = true;
            return new CacheEntry(value, System.currentTimeMillis() + ttlMillis, timestamp);
        });
        if (applied[0]) {
            tombstones.computeIfPresent(key, (k, t) -> t < timestamp ? null : t);
            evictIfNeeded();
        }
        return applied[0];
    }

    /**
     * Ap dung 1 lenh REPLICATE|DELETE: xoa key neu ban ghi hien co cu hon timestamp,
     * va luon ghi tombstone de chan PUT cu den tre.
     *
     * @return true neu da xoa 1 ban ghi ton tai
     */
    public boolean deleteReplicated(String key, long timestamp) {
        tombstones.merge(key, timestamp, Math::max);
        boolean[] removed = {false};
        store.compute(key, (k, existing) -> {
            if (existing != null && existing.timestamp() < timestamp) {
                removed[0] = true;
                return null;
            }
            return existing;
        });
        return removed[0];
    }

    public int size() {
        return store.size();
    }

    /** Xoa cac key het han TTL va tombstone qua cu. Chay dinh ky boi ttlSweeper. */
    void sweepExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> e : store.entrySet()) {
            if (e.getValue().isExpired(now) && store.remove(e.getKey(), e.getValue())) {
                removed++;
            }
        }
        tombstones.values().removeIf(ts -> now - ts > ttlMillis);
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
