package com.nhom05.cache.registry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Module 4 - Gui heartbeat dinh ky tu Cache Server toi RegistryServer.
 * Chay tren thread nen daemon, cap nhat so key va so request hien tai cua Cache Server.
 */
public final class HeartbeatSender implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(HeartbeatSender.class.getName());

    public static final long DEFAULT_INTERVAL_SECONDS = 3;

    private final String registryHost;
    private final int registryPort;
    private final int serverIndex;
    private final String selfHost;
    private final int selfPort;
    private final long intervalSeconds;
    private final IntSupplier keyCount;
    private final LongSupplier requestCount;
    private final RegistryClient client;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(true);

    public HeartbeatSender(String registryHost, int registryPort, int serverIndex,
                           String selfHost, int selfPort, long intervalSeconds,
                           IntSupplier keyCount, LongSupplier requestCount) {
        this.registryHost = registryHost;
        this.registryPort = registryPort;
        this.serverIndex = serverIndex;
        this.selfHost = selfHost;
        this.selfPort = selfPort;
        this.intervalSeconds = intervalSeconds > 0 ? intervalSeconds : DEFAULT_INTERVAL_SECONDS;
        this.keyCount = keyCount;
        this.requestCount = requestCount;
        this.client = new RegistryClient(registryHost, registryPort);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-sender-" + serverIndex);
            t.setDaemon(true);
            return t;
        });
    }

    public HeartbeatSender(String registryHost, int registryPort, int serverIndex,
                           String selfHost, int selfPort,
                           IntSupplier keyCount, LongSupplier requestCount) {
        this(registryHost, registryPort, serverIndex, selfHost, selfPort, DEFAULT_INTERVAL_SECONDS,
                keyCount, requestCount);
    }

    /**
     * Bat dau gui heartbeat: gui ngay lap tuc lan dau tien, sau do dinh ky moi intervalSeconds.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::sendOnce, 0, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Dung gui heartbeat va giai phong scheduler.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Thuc hien gui 1 lan heartbeat.
     * Package-private de phuc vu unit test kiem tra dong bo.
     */
    boolean sendOnce() {
        int kc = keyCount != null ? keyCount.getAsInt() : 0;
        long rc = requestCount != null ? requestCount.getAsLong() : 0;
        boolean ok = client.sendHeartbeat(serverIndex, selfHost, selfPort, kc, rc);
        if (ok) {
            if (connected.compareAndSet(false, true)) {
                LOGGER.info(() -> "Da ket noi lai toi Registry tai " + registryHost + ":" + registryPort);
            }
        } else {
            if (connected.compareAndSet(true, false)) {
                LOGGER.warning(() -> "Mat ket noi toi Registry tai " + registryHost + ":" + registryPort);
            }
        }
        return ok;
    }
}
