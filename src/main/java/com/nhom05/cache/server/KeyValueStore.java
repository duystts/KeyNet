package com.nhom05.cache.server;

import java.util.ArrayList;
import java.util.List;
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
 * - Giai quyet xung dot phan tan (LWW + Tombstone):
 *   + Bo dem timestamp tang nghiem ngat nextTimestamp()
 *   + Last-Write-Wins cho put() va putReplicated()
 *   + Tombstone khi delete() va deleteReplicated() ngan PUT cu den tre lam key song lai
 *   + Check-then-act nguyen tu qua ConcurrentHashMap.compute
 */
public final class KeyValueStore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(KeyValueStore.class.getName());

    /**
     * Tombstone ghi nhan thoi diem xoa cua 1 key de ngan chan ban tin PUT cu den tre lam key song lai.
     * Tu dong bi don dep sau 1 chu ky TTL.
     */
    static final class Tombstone {
        private final long timestamp;
        private final long expireAtMillis;

        Tombstone(long timestamp, long expireAtMillis) {
            this.timestamp = timestamp;
            this.expireAtMillis = expireAtMillis;
        }

        long timestamp() {
            return timestamp;
        }

        long expireAtMillis() {
            return expireAtMillis;
        }

        boolean isExpired(long nowMillis) {
            return nowMillis >= expireAtMillis;
        }
    }

    public record Snapshot(String key, String value, long timestamp) { }

    private final ConcurrentHashMap<String, CacheEntry> map = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Tombstone> tombstones = new ConcurrentHashMap<>();

    /** Bo dem thu tu truy cap cho LRU (tang dan, khong trung nhu dung thoi gian). */
    private final AtomicLong accessCounter = new AtomicLong();

    /** Bo dem timestamp tang nghiem ngat cho cac lan ghi. */
    private final AtomicLong lastTimestamp = new AtomicLong();

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

    /**
     * Bo dem timestamp tang nghiem ngat theo co che nguyen tu:
     * max(clock, last + 1). Dam bao an toan tuyet doi trong moi truong da luong.
     */
    public long nextTimestamp() {
        long now = clock.getAsLong();
        return lastTimestamp.accumulateAndGet(now, (cur, n) -> Math.max(n, cur + 1));
    }

    /**
     * Tu dong cap nhat bo dem timestamp khi nhan duoc timestamp tu server khac qua replication:
     * last.set(Math.max(last.get(), timestamp)).
     */
    public void updateLastTimestamp(long timestamp) {
        lastTimestamp.accumulateAndGet(timestamp, Math::max);
    }

    /** Lay gia tri hien tai cua bo dem timestamp. */
    public long getLastTimestamp() {
        return lastTimestamp.get();
    }

    /** Thoi gian hien tai theo dong ho cua kho. */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * Ghi hoac ghi de key voi timestamp tang nghiem ngat tu bo dem.
     * Tra ve timestamp cua lan ghi do.
     */
    public long put(String key, String value) {
        long ts = nextTimestamp();
        putInternal(key, value, ts);
        return ts;
    }

    private void putInternal(String key, String value, long timestamp) {
        long now = clock.getAsLong();
        map.compute(key, (k, existing) -> {
            tombstones.remove(k);
            return new CacheEntry(value, timestamp, now + ttlMillis, accessCounter.incrementAndGet());
        });
        evictIfNeeded();
    }

    /**
     * Ghi ban tin replication tu server khac theo co che Last-Write-Wins (LWW).
     * Chi cho phep ghi khi timestamp moi lon hon timestamp hien tai cua key
     * va lon hon tombstone (neu co). Neu cu hon hoac bang thi bo qua va tra ve false.
     */
    public boolean putReplicated(String key, String value, long timestamp) {
        updateLastTimestamp(timestamp);
        long now = clock.getAsLong();
        boolean[] written = new boolean[1];

        map.compute(key, (k, existing) -> {
            // Kiem tra tombstone con han
            Tombstone tombstone = tombstones.get(k);
            if (tombstone != null) {
                if (tombstone.isExpired(now)) {
                    tombstones.remove(k, tombstone);
                } else if (timestamp <= tombstone.timestamp()) {
                    // PUT cu hon hoac bang tombstone -> bo qua, khong lam song lai key
                    return existing;
                }
            }

            // Kiem tra ban ghi hien tai trong map (neu co)
            if (existing != null && timestamp <= existing.timestamp()) {
                // PUT cu hon hoac bang ban ghi hien co -> bo qua
                return existing;
            }

            // Ghi de: xoa tombstone (neu co) va tao entry moi
            tombstones.remove(k);
            written[0] = true;
            return new CacheEntry(value, timestamp, now + ttlMillis, accessCounter.incrementAndGet());
        });

        if (written[0]) {
            evictIfNeeded();
        }
        return written[0];
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

    /** Lay timestamp cua key; tra ve -1 neu key khong co hoac da het han. */
    public long getTimestamp(String key) {
        CacheEntry entry = map.get(key);
        if (entry == null || entry.isExpired(clock.getAsLong())) {
            return -1L;
        }
        return entry.timestamp();
    }

    /**
     * Xoa key voi timestamp tu dong sinh ra; de lai tombstone ngan PUT cu den tre.
     * Tra ve true neu key ton tai (va chua het han) truoc khi xoa.
     */
    public boolean delete(String key) {
        return delete(key, nextTimestamp());
    }

    /**
     * Xoa key voi timestamp xac dinh va de lai tombstone.
     * Tra ve true neu key ton tai (va chua het han) truoc khi xoa.
     */
    public boolean delete(String key, long timestamp) {
        updateLastTimestamp(timestamp);
        long now = clock.getAsLong();
        boolean[] deleted = new boolean[1];

        map.compute(key, (k, existing) -> {
            Tombstone tombstone = tombstones.get(k);
            if (tombstone != null && !tombstone.isExpired(now) && timestamp <= tombstone.timestamp()) {
                return existing;
            }

            if (existing != null) {
                if (timestamp < existing.timestamp()) {
                    return existing;
                }
                if (!existing.isExpired(now)) {
                    deleted[0] = true;
                }
            }

            tombstones.put(k, new Tombstone(timestamp, now + ttlMillis));
            return null;
        });

        return deleted[0];
    }

    /**
     * Xoa ban tin replication tu server khac theo co che Last-Write-Wins (LWW) va de lai tombstone.
     * Chi xoa neu timestamp moi lon hon hoac bang timestamp ban ghi hien tai va tombstone hien tai.
     * Tra ve true neu thao tac xoa/tombstone duoc ap dung, false neu bi bo qua vi cu hon.
     */
    public boolean deleteReplicated(String key, long timestamp) {
        updateLastTimestamp(timestamp);
        long now = clock.getAsLong();
        boolean[] applied = new boolean[1];

        map.compute(key, (k, existing) -> {
            Tombstone tombstone = tombstones.get(k);
            if (tombstone != null && !tombstone.isExpired(now) && timestamp <= tombstone.timestamp()) {
                // DELETE cu hon hoac bang tombstone -> bo qua
                return existing;
            }

            if (existing != null && timestamp < existing.timestamp()) {
                // DELETE cu hon ban ghi hien co -> bo qua
                return existing;
            }

            tombstones.put(k, new Tombstone(timestamp, now + ttlMillis));
            applied[0] = true;
            return null;
        });

        return applied[0];
    }

    /** So key dang luu (co the gom ca key het han ma sweeper chua kip don). */
    public int size() {
        return map.size();
    }

    /** So luong tombstone hien co trong kho. */
    int tombstoneCount() {
        return tombstones.size();
    }

    /** Xoa cac key het han va tombstone het han. Sweeper goi moi giay; test goi truc tiep. */
    int sweepExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> e : map.entrySet()) {
            if (e.getValue().isExpired(now) && map.remove(e.getKey(), e.getValue())) {
                removed++;
            }
        }
        for (Map.Entry<String, Tombstone> e : tombstones.entrySet()) {
            if (e.getValue().isExpired(now)) {
                tombstones.remove(e.getKey(), e.getValue());
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

    /** Chup anh toan bo key chua het han (phuc vu co che SYNC khoi dong lai). */
    public List<Snapshot> snapshot() {
        long now = clock.getAsLong();
        List<Snapshot> list = new ArrayList<>();
        for (Map.Entry<String, CacheEntry> e : map.entrySet()) {
            CacheEntry entry = e.getValue();
            if (entry != null && !entry.isExpired(now)) {
                list.add(new Snapshot(e.getKey(), entry.value(), entry.timestamp()));
            }
        }
        return list;
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }
}
