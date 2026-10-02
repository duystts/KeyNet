package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 1 - Core Cache Server.
 *
 * Lang nghe TCP tren 1 port, moi ket noi client duoc giao cho 1 thread
 * trong thread pool co dinh xu ly (xem muc 1 va 8 cua dac ta giao thuc).
 *
 * Chay: java -jar keynet.jar config/config.properties
 * (mac dinh doc config/config.properties neu khong truyen tham so)
 */
public final class CacheServer {

    private static final Logger LOGGER = Logger.getLogger(CacheServer.class.getName());
    private static final int THREAD_POOL_SIZE = 50;

    private final int port;
    private final KeyValueStore store;
    private final ExecutorService pool;
    private volatile boolean running = true;

    public CacheServer(int port, long ttlSeconds, int maxEntries) {
        this.port = port;
        this.store = new KeyValueStore(ttlSeconds, maxEntries);
        this.pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LOGGER.info(() -> "CacheServer dang lang nghe tai port " + port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    pool.execute(new ClientHandler(client, store));
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
        pool.shutdown();
        store.close();
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config/config.properties";
        try {
            ServerConfig config = ServerConfig.load(configPath);
            ServerConfig.ServerAddress self = config.self();

            CacheServer server = new CacheServer(self.port(), config.ttlSeconds(), config.maxEntries());
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

            LOGGER.info(() -> "KeyNet CacheServer #" + config.serverIndex()
                    + " - ttl=" + config.ttlSeconds() + "s, maxEntries=" + config.maxEntries());
            server.start();

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Khong the doc file cau hinh: " + configPath, e);
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
