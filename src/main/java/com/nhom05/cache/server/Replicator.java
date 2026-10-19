package com.nhom05.cache.server;

/**
 * Noi Module 1 voi Module 3: ClientHandler goi sau moi PUT/DELETE thanh cong de day ban ghi
 * sang server du phong. Cai dat that la ReplicationHandler; cum 1 server dung NOOP.
 */
public interface Replicator {

    /** Khong gui di dau (cum 1 server, hoac test chi can 1 server). */
    Replicator NOOP = new Replicator() {
        @Override
        public void replicatePut(String key, String value, long timestamp) {
        }

        @Override
        public void replicateDelete(String key, long timestamp) {
        }
    };

    /** Phai tra ve ngay (gui bat dong bo), khong duoc nem exception. */
    void replicatePut(String key, String value, long timestamp);

    void replicateDelete(String key, long timestamp);
}
