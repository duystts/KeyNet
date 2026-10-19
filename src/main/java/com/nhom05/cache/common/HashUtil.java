package com.nhom05.cache.common;

/**
 * Cong thuc chon server (muc 4 dac ta), dung chung cho Client va Replication:
 *
 *     serverIndex  = |hash(key)| mod N      (hash = String.hashCode())
 *     replicaIndex = (serverIndex + 1) mod N
 *
 * N la tong so server trong file cau hinh (khong phai so server dang song), de moi client
 * tinh ra cung 1 ket qua.
 */
public final class HashUtil {

    private HashUtil() {
    }

    /** Chi so server chinh (0..n-1) phu trach key. */
    public static int computeServerIndex(String key, int n) {
        requirePositive(n);
        // Lay mod truoc roi moi abs. Neu viet Math.abs(hash) % n: voi hash = Integer.MIN_VALUE,
        // Math.abs tran so va van tra so am -> chi so am -> crash. Ket qua 2 cach giong nhau
        // voi moi hash khac, va van dung la |hash| mod n.
        return Math.abs(key.hashCode() % n);
    }


    /** Chi so server du phong cua serverIndex: server ke tiep, quay vong ve 0. */
    public static int replicaIndex(int serverIndex, int n) {
        requirePositive(n);
        return (serverIndex + 1) % n;
    }

    private static void requirePositive(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("So luong server phai > 0, nhan duoc: " + n);
        }
    }
}
