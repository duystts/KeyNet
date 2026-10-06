package com.nhom05.cache.server;

/**
 * Cong de Module 1 bao cho Module 3 (Replication) biet 1 thao tac ghi vua thanh cong
 * tren server nay, de dong bo sang server du phong (muc 5 dac ta, bat dong bo).
 *
 * Dat interface o package server de server KHONG phu thuoc nguoc vao package replication
 * (tranh vong phu thuoc giua 2 package).
 */
public interface Replicator extends AutoCloseable {

    /** Khong lam gi - dung khi chay 1 server don le hoac chua cau hinh replication. */
    Replicator NOOP = new Replicator() {
        @Override
        public void replicatePut(String key, String value, long timestamp) {
        }

        @Override
        public void replicateDelete(String key, long timestamp) {
        }
    };

    void replicatePut(String key, String value, long timestamp);

    void replicateDelete(String key, long timestamp);

    @Override
    default void close() {
    }
}
