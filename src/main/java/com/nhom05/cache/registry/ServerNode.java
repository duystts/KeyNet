package com.nhom05.cache.registry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Module 4 - Monitoring / Registry + Health Check.
 *
 * Dai dien cho thong tin va trang thai cua mot Cache Server duoc Registry theo doi.
 */
public class ServerNode {

    public enum Status {
        UP,
        DOWN
    }

    private final int serverIndex;
    private volatile String host;
    private volatile int port;
    private final AtomicLong lastHeartbeatTimestamp;
    private volatile int keyCount;
    private final AtomicLong requestCount;
    private volatile Status status;

    public ServerNode(int serverIndex, String host, int port) {
        this(serverIndex, host, port, 0, 0);
    }

    public ServerNode(int serverIndex, String host, int port, int keyCount, long requestCount) {
        this.serverIndex = serverIndex;
        this.host = Objects.requireNonNull(host, "host cannot be null");
        this.port = port;
        this.lastHeartbeatTimestamp = new AtomicLong(System.currentTimeMillis());
        this.keyCount = keyCount;
        this.requestCount = new AtomicLong(requestCount);
        this.status = Status.UP;
    }

    /**
     * Cap nhat heartbeat tu Cache Server.
     */
    public void recordHeartbeat(String host, int port) {
        this.host = host;
        this.port = port;
        this.lastHeartbeatTimestamp.set(System.currentTimeMillis());
        this.status = Status.UP;
    }

    /**
     * Cap nhat heartbeat kem thong so thong ke (keyCount, requestCount).
     */
    public void recordHeartbeat(String host, int port, int keyCount, long requestCount) {
        recordHeartbeat(host, port);
        this.keyCount = keyCount;
        this.requestCount.set(requestCount);
    }

    public int getServerIndex() {
        return serverIndex;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp.get();
    }

    public int getKeyCount() {
        return keyCount;
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /**
     * Kiem tra xem server con song hay khong theo nguong timeout (default 9000ms).
     */
    public boolean isAlive(long now, long timeoutMillis) {
        return (now - lastHeartbeatTimestamp.get()) <= timeoutMillis;
    }

    @Override
    public String toString() {
        return "ServerNode{" +
                "serverIndex=" + serverIndex +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", lastHeartbeatTimestamp=" + lastHeartbeatTimestamp +
                ", keyCount=" + keyCount +
                ", requestCount=" + requestCount +
                ", status=" + status +
                '}';
    }
}
