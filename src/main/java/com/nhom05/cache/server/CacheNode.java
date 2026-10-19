package com.nhom05.cache.server;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.common.ServerConfig.ServerAddress;
import com.nhom05.cache.registry.HeartbeatSender;
import com.nhom05.cache.replication.ReplicationHandler;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * 1 node day du cua cum: Cache Server (Module 1) + Replication (Module 3) + Heartbeat (Module 4).
 *
 * Thu tu khoi dong (muc 5 dac ta trien khai):
 *   1. bat listener replication (port client + 1000) va dat server du phong (i+1) mod N
 *   2. recover: lay lai du lieu tu 2 server ke TRUOC khi nhan client
 *      (luc nay client van failover sang server du phong)
 *   3. mo port client
 *   4. recover them 1 lan: bat cac lan ghi lot vao giua buoc 2 va 3
 *   5. bat HeartbeatSender: Registry chi bao UP khi server da lay lai du lieu
 */
public final class CacheNode implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CacheNode.class.getName());
    private static final long START_TIMEOUT_MS = 5000;

    private final ServerConfig config;
    private final CacheServer server;
    /** null khi cum chi co 1 server (khong co ai de nhan ban). */
    private final ReplicationHandler replication;
    private final HeartbeatSender heartbeat;
    private volatile Thread serverThread;
    private volatile IOException startFailure;

    public CacheNode(ServerConfig config) {
        this(config, HeartbeatSender.DEFAULT_INTERVAL_SECONDS);
    }

    /** Cho test dat chu ky heartbeat ngan. */
    CacheNode(ServerConfig config, long heartbeatIntervalSeconds) {
        this.config = config;
        ServerAddress self = config.self();
        this.server = new CacheServer(self.port(), config.ttlSeconds(), config.maxEntries());
        this.replication = config.serverCount() > 1 ? new ReplicationHandler(server.store(), config) : null;
        ServerAddress registry = config.registry();
        this.heartbeat = new HeartbeatSender(registry.host(), registry.port(), config.serverIndex(),
                self.host(), self.port(), heartbeatIntervalSeconds, server::keyCount, server::requestCount);
    }

    public CacheServer server() {
        return server;
    }

    /** Viec lam khi GUI gui SHUTDOWN toi port replication (#16). Cum 1 server khong ho tro. */
    public void setShutdownAction(Runnable action) {
        if (replication != null) {
            replication.setShutdownAction(action);
        }
    }

    /** Khoi dong theo thu tu o tren; tra ve khi port client da mo. Loi mo port -> IOException. */
    public void start() throws IOException {
        int index = config.serverIndex();
        if (replication != null) {
            replication.setConfig(config);
            replication.startListener(config.replicationPort(index));
            int backup = config.backupIndex();
            replication.setBackup(config.servers().get(backup).host(), config.replicationPort(backup));
            server.setReplicator(replication);
            replication.recover(config);
        }

        // Thread thuong (khong daemon): giu JVM song khi chay bang main
        serverThread = new Thread(this::runServer, "cache-server-" + index);
        serverThread.start();
        awaitClientPort();

        if (replication != null) {
            replication.recover(config);
        }
        heartbeat.start();
        LOGGER.info("KeyNet CacheServer #" + index + " san sang - port " + config.self().port()
                + ", ttl=" + config.ttlSeconds() + "s, maxEntries=" + config.maxEntries()
                + ", du lieu hien co " + server.keyCount() + " key");
    }

    private void runServer() {
        try {
            server.start();
        } catch (IOException e) {
            startFailure = e;
        }
    }

    private void awaitClientPort() throws IOException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        try {
            while (!server.awaitListening(50)) {
                if (!serverThread.isAlive() || System.currentTimeMillis() > deadline) {
                    close();
                    IOException cause = startFailure;
                    throw new IOException("Khong mo duoc port " + config.self().port()
                            + (cause != null ? ": " + cause.getMessage() : ""), cause);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Bi ngat khi cho mo port " + config.self().port(), e);
        }
    }

    /** Dung heartbeat truoc (Registry se bao DOWN sau timeout), roi dong port client va replication. */
    @Override
    public void close() {
        heartbeat.stop();
        server.shutdown();
        if (replication != null) {
            replication.close();
        }
    }
}
