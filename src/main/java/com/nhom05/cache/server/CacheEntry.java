package com.nhom05.cache.server;

/**
 * 1 ban ghi trong kho: gia tri + thoi diem het han (TTL) + thu tu truy cap gan nhat (LRU).
 *
 * Package-private: chi KeyValueStore thao tac truc tiep, module khac di qua API cua
 * KeyValueStore de khong lam sai TTL / LRU / gioi han so key.
 */
final class CacheEntry {

    private final String value;
    private final long timestamp;
    private final long expireAtMillis;
    /** So thu tu lan truy cap gan nhat (lon hon = moi hon). volatile vi GET o nhieu thread cap nhat. */
    private volatile long lastAccess;

    CacheEntry(String value, long expireAtMillis, long accessOrder) {
        this(value, 0L, expireAtMillis, accessOrder);
    }

    CacheEntry(String value, long timestamp, long expireAtMillis, long accessOrder) {
        this.value = value;
        this.timestamp = timestamp;
        this.expireAtMillis = expireAtMillis;
        this.lastAccess = accessOrder;
    }

    String value() {
        return value;
    }

    long timestamp() {
        return timestamp;
    }

    boolean isExpired(long nowMillis) {
        return nowMillis >= expireAtMillis;
    }

    long lastAccess() {
        return lastAccess;
    }

    void touch(long accessOrder) {
        this.lastAccess = accessOrder;
    }
}
