package com.nhom05.cache.gui;

/**
 * Ban ghi luu tru thong tin mot key trong Key Explorer.
 */
public record KeyEntry(
        String key,
        String value,
        int primaryIndex,
        int replicaIndex,
        String lastUpdatedTime
) {
}
