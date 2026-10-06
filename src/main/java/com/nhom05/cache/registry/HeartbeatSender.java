package com.nhom05.cache.registry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Thread nen gui Heartbeat dinh ky moi 3 giay tu Cache Server toi Registry (port 6000),
 * theo dung quy dinh muc 6 trong tai lieu dac ta giao thuc.
 */
public class HeartbeatSender {

    private static final Logger LOGGER = Logger.getLogger(HeartbeatSender.class.getName());

    public static final long DEFAULT_INTERVAL_SECONDS = 3L;

    private final String registryHost;
    private final int registryPort;
    private final int serverIndex;
    private final String selfHost;
    private final int selfPort;
    private final long intervalSeconds;

    private final RegistryClient client;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    private IntSupplier keyCountSupplier = () -> 0;
    private LongSupplier requestCountSupplier = () -> 0L;

    public HeartbeatSender(String registryHost, int registryPort, int serverIndex, String selfHost, int selfPort) {
        this(registryHost, registryPort, serverIndex, selfHost, selfPort, DEFAULT_INTERVAL_SECONDS);
    }

    public HeartbeatSender(String registryHost, int registryPort, int serverIndex, String selfHost, int selfPort, long intervalSeconds) {
        this.registryHost = registryHost;
        this.registryPort = registryPort;
        this.serverIndex = serverIndex;
        this.selfHost = selfHost;
        this.selfPort = selfPort;
        this.intervalSeconds = intervalSeconds;
        this.client = new RegistryClient(registryHost, registryPort);
    }

    public void setMetricsSuppliers(IntSupplier keyCountSupplier, LongSupplier requestCountSupplier) {
        this.keyCountSupplier = keyCountSupplier != null ? keyCountSupplier : () -> 0;
        this.requestCountSupplier = requestCountSupplier != null ? requestCountSupplier : () -> 0L;
    }

    /**
     * Bat dau thread gui Heartbeat dinh ky.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatSender-Server#" + serverIndex);
            t.setDaemon(true);
            return t;
        });

        LOGGER.info(() -> "[HEARTBEAT-SENDER] Bat dau gui heartbeat cho Server #" + serverIndex
                + " (" + selfHost + ":" + selfPort + ") toi Registry " + registryHost + ":" + registryPort
                + " moi " + intervalSeconds + "s");

        scheduler.scheduleAtFixedRate(
                this::sendOnce,
                0,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Gui 1 lan heartbeat toi Registry.
     */
    public boolean sendOnce() {
        int keys = keyCountSupplier.getAsInt();
        long reqs = requestCountSupplier.getAsLong();
        boolean ok = client.sendHeartbeat(serverIndex, selfHost, selfPort, keys, reqs);
        if (ok) {
            LOGGER.fine(() -> "[HEARTBEAT-SENDER] Heartbeat Server #" + serverIndex + " thanh cong (ACK).");
        } else {
            LOGGER.warning(() -> "[HEARTBEAT-SENDER] Khong the gui heartbeat Server #" + serverIndex
                    + " toi Registry tai " + registryHost + ":" + registryPort);
        }
        return ok;
    }

    /**
     * Dung thread heartbeat (khi Cache Server shutdown).
     */
    public synchronized void stop() {
        running = false;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        LOGGER.info(() -> "[HEARTBEAT-SENDER] Da dung heartbeat cho Server #" + serverIndex);
    }

    public boolean isRunning() {
        return running;
    }
}
