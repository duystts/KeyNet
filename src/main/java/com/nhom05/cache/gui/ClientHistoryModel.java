package com.nhom05.cache.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model quan ly du lieu cho Key Explorer va nhat ky lenh cua GUI Client.
 * Duoc thiet ke doc lap voi Swing components de de dang kiem thu Unit Test.
 */
public class ClientHistoryModel {

    private final Map<String, KeyEntry> keyStore = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<CommandRecord> commandLogs = Collections.synchronizedList(new ArrayList<>());
    private final List<Runnable> changeListeners = new ArrayList<>();

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    /**
     * Them hoac cap nhat key sau khi PUT thanh cong.
     */
    public synchronized void putKey(String key, String value, int primaryIndex, int replicaIndex) {
        if (key == null || key.isBlank()) return;
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        keyStore.put(key, new KeyEntry(key, value != null ? value : "", primaryIndex, replicaIndex, time));
        notifyChanged();
    }

    /**
     * Xoa key khoi Key Explorer khi DELETE tra ve OK hoac GET tra ve NOTFOUND.
     */
    public synchronized boolean removeKey(String key) {
        if (key == null) return false;
        KeyEntry removed = keyStore.remove(key);
        if (removed != null) {
            notifyChanged();
            return true;
        }
        return false;
    }

    /**
     * Cap nhat gia tri moi cho key khi GET thanh cong.
     */
    public synchronized void updateValueIfPresent(String key, String newValue) {
        KeyEntry current = keyStore.get(key);
        if (current != null) {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            keyStore.put(key, new KeyEntry(current.key(), newValue != null ? newValue : "",
                    current.primaryIndex(), current.replicaIndex(), time));
            notifyChanged();
        }
    }

    /**
     * Ghi nhan mot thao tac lenh vao nhat ky lich su.
     */
    public synchronized void logCommand(String command, String key, String value,
                                        int servedBy, boolean failedOver, String responseLine) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        boolean success = isSuccessResponse(command, responseLine);
        CommandRecord record = new CommandRecord(time, command, key, value, servedBy, failedOver, responseLine, success);
        commandLogs.add(0, record); // Dong moi nhat len dau
        notifyChanged();
    }

    private boolean isSuccessResponse(String command, String responseLine) {
        if (responseLine == null) return false;
        String upper = responseLine.toUpperCase();
        if ("PUT".equalsIgnoreCase(command) || "DELETE".equalsIgnoreCase(command)) {
            return upper.startsWith("OK");
        }
        if ("GET".equalsIgnoreCase(command)) {
            return upper.startsWith("VALUE|") || upper.equals("NOTFOUND");
        }
        if ("INFO".equalsIgnoreCase(command)) {
            return true;
        }
        return !upper.startsWith("ERROR|");
    }

    public synchronized List<KeyEntry> allKeys() {
        return List.copyOf(keyStore.values());
    }

    public synchronized List<CommandRecord> allCommandLogs() {
        return List.copyOf(commandLogs);
    }

    public synchronized KeyEntry findKey(String key) {
        return keyStore.get(key);
    }

    public synchronized int keyCount() {
        return keyStore.size();
    }

    public synchronized void clearHistory() {
        commandLogs.clear();
        notifyChanged();
    }

    public synchronized void clearAllKeys() {
        keyStore.clear();
        notifyChanged();
    }
}
