package com.nhom05.cache.server;

/**
 * Mot ban ghi trong Key-Value Store: gia tri + thoi diem het han (TTL)
 * + thoi diem truy cap gan nhat (dung cho LRU eviction).
 */
final class CacheEntry {

    private final String value;
    private final long expireAtMillis;
    // Dung nanoTime (khong phai epoch millis) chi de SO SANH thu tu truy cap gan day:
    // do phan giai millis qua tho, 2 lan ghi lien tiep co the trung timestamp
    // va lam sai thu tu LRU. nanoTime chi dung noi bo, khong dung de hien thi/luu tru.
    private volatile long lastAccessNanos;

    CacheEntry(String value, long expireAtMillis) {
        this.value = value;
        this.expireAtMillis = expireAtMillis;
        this.lastAccessNanos = System.nanoTime();
    }

    String value() {
        return value;
    }

    boolean isExpired(long now) {
        return now >= expireAtMillis;
    }

    long lastAccessNanos() {
        return lastAccessNanos;
    }

    void touch() {
        this.lastAccessNanos = System.nanoTime();
    }
}
