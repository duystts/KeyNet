package com.nhom05.cache.gui;

/**
 * Thong tin hien thi cho 1 node Cache Server tren Dashboard.
 */
public record NodeDisplayInfo(
        int index,
        String host,
        int clientPort,
        int replicationPort,
        String status,
        boolean isUp,
        int keyCount,
        long requestCount,
        int backupForIndex
) {
}
