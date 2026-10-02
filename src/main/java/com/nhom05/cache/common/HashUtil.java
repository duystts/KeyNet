package com.nhom05.cache.common;

/**
 * Cong thuc hashing dung chung - xem muc 4 trong tai lieu dac ta giao thuc.
 *
 *     serverIndex = |hash(key)| mod N
 *
 * Module 2 (Client) dung ham nay de chon Cache Server; Module 3 (Replication)
 * dung de tinh server du phong (serverIndex + 1) mod N.
 */
public final class HashUtil {

    private HashUtil() {
    }

    /**
     * Tra ve chi so server (0..n-1) phu trach key nay.
     *
     * @param key chuoi key (khong null)
     * @param n   tong so Cache Server dang cau hinh (phai > 0)
     */
    public static int computeServerIndex(String key, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("So luong server (n) phai > 0");
        }
        return Math.abs(key.hashCode()) % n;
    }

    /** Server du phong (dung cho replication) cua mot serverIndex. */
    public static int replicaIndex(int serverIndex, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("So luong server (n) phai > 0");
        }
        return (serverIndex + 1) % n;
    }
}
