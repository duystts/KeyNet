package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.HeartbeatSender;
import com.nhom05.cache.replication.ReplicationHandler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 1 - Core Cache Server.
 *
 * Lang nghe TCP tren 1 port, moi ket noi client duoc giao cho 1 thread
 * trong thread pool co dinh xu ly (xem muc 1 va 8 cua dac ta giao thuc).
 *
 * Chay: java -jar keynet.jar [config/config.properties] [serverIndex]
 * (mac dinh doc config/config.properties; serverIndex ghi de cache.serverIndex trong file
 * de chay nhieu instance tu cung 1 file config)
 */
public final class CacheServer {

    private static final Logger LOGGER = Logger.getLogger(CacheServer.class.getName());
    private static final int THREAD_POOL_SIZE = 50;

    private final int port;
    private final KeyValueStore store;
    private final ExecutorService pool;
    private volatile boolean running = true;
    private volatile ServerSocket listener;
    private volatile Replicator replicator = Replicator.NOOP;
    /** So request da nhan (connection-per-request), gui kem heartbeat cho dashboard (Module 5). */
    private final LongAdder requestCount = new LongAdder();

    public CacheServer(int port, long ttlSeconds, int maxEntries) {
        this.port = port;
        this.store = new KeyValueStore(ttlSeconds, maxEntries);
        this.pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    /** Kho du lieu cua server nay (Module 3 dung de gan listener nhan REPLICATE). */
    public KeyValueStore store() {
        return store;
    }

    /** Tong so request client da gui toi server nay ke tu khi khoi dong. */
    public long requestCount() {
        return requestCount.sum();
    }

    /** Gan Module 3 (Replication) vao server; goi truoc start(). */
    public void setReplicator(Replicator replicator) {
        this.replicator = replicator == null ? Replicator.NOOP : replicator;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            listener = serverSocket;
            LOGGER.info(() -> "CacheServer dang lang nghe tai port " + port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    requestCount.increment();
                    pool.execute(new ClientHandler(client, store, replicator));
                } catch (RejectedExecutionException e) {
                    // Thread pool qua tai - khong the xu ly them ket noi luc nay.
                    LOGGER.warning(() -> "Server qua tai (" + ErrorCode.E004.code()
                            + "), tu choi ket noi moi tam thoi.");
                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Loi accept ket noi: " + e.getMessage(), e);
                    }
                }
            }
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        running = false;
        // Dong ServerSocket de accept() dang chan thoat ra va nha port ngay
        // (neu khong, server "da tat" van giu port va nuot ket noi moi).
        try {
            ServerSocket s = listener;
            if (s != null) {
                s.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Loi dong ServerSocket: " + e.getMessage(), e);
        }
        pool.shutdown();
        replicator.close();
        store.close();
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config/config.properties";
        try {
            ServerConfig loaded = ServerConfig.load(configPath);
            ServerConfig config = args.length > 1
                    ? loaded.withServerIndex(Integer.parseInt(args[1]))
                    : loaded;
            ServerConfig.ServerAddress self = config.self();

            CacheServer server = new CacheServer(self.port(), config.ttlSeconds(), config.maxEntries());
            if (config.serverCount() > 1) {
                ReplicationHandler replication = new ReplicationHandler(server.store());
                ServerConfig.ServerAddress backup = config.servers().get(config.backupIndex());
                replication.configureBackupServer(backup.host(),
                        config.replicationPort(config.backupIndex()));
                replication.startListener(config.replicationPort(config.serverIndex()));
                server.setReplicator(replication);
            }
            // Module 4: heartbeat dinh ky toi Registry kem so key / so request (cho STATS)
            ServerConfig.ServerAddress registry = config.registry();
            HeartbeatSender heartbeat = new HeartbeatSender(registry.host(), registry.port(),
                    config.serverIndex(), self.host(), self.port());
            heartbeat.setMetricsSuppliers(server.store()::size, server::requestCount);
            heartbeat.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                heartbeat.stop();
                server.shutdown();
            }));

            LOGGER.info(() -> "KeyNet CacheServer #" + config.serverIndex()
                    + " - ttl=" + config.ttlSeconds() + "s, maxEntries=" + config.maxEntries());
            server.start();

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Khong the doc file cau hinh: " + configPath, e);
            System.exit(1);
        } catch (IllegalArgumentException e) {
            LOGGER.severe("serverIndex khong hop le: " + e.getMessage());
            System.exit(1);
        }
    }

    // Goi tu test de kiem tra parser/response end-to-end ma khong can mo that ServerSocket.
    static String handleRaw(KeyValueStore store, String requestLine) {
        return new ClientHandler(null, store).handle(requestLine);
    }

    static {
        // Dam bao cac log message hien thi duoc tieng Viet co dau tren console.
        System.setProperty("file.encoding", "UTF-8");
    }

    // Expose lenh tao response loi cu phap cho noi khac trong module neu can.
    static String invalidSyntax() {
        return ProtocolParser.error(ErrorCode.E001);
    }
}
