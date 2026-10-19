package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.HeartbeatSender;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 1 - Cache Server: lang nghe TCP, moi ket noi giao cho 1 thread trong pool,
 * doc 1 dong request -> tra 1 dong response -> dong ket noi (muc 1, 8 dac ta).
 */
public final class CacheServer {

    private static final Logger LOGGER = Logger.getLogger(CacheServer.class.getName());

    static final int THREAD_POOL_SIZE = 50;
    /** So ket noi toi da duoc xep hang cho thread ranh; vuot qua thi tra E004. */
    static final int QUEUE_CAPACITY = 200;
    /** Ket noi mo ma im lang qua lau thi dong, tranh client bo quen (vd telnet) giu thread mai. */
    static final int IDLE_TIMEOUT_MS = 30_000;
    /** Dong request dai nhat hop le: "PUT|" + key + "|" + value + chut du. */
    static final int MAX_LINE_LENGTH = ProtocolParser.MAX_KEY_LENGTH + ProtocolParser.MAX_VALUE_LENGTH + 16;

    private final int port;
    private final KeyValueStore store;
    private final ClientHandler handler;
    private final ThreadPoolExecutor pool;
    private final CountDownLatch listening = new CountDownLatch(1);
    private final AtomicLong requestCount = new AtomicLong();
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;

    public CacheServer(int port, long ttlSeconds, int maxEntries) {
        this(port, new KeyValueStore(ttlSeconds, maxEntries), THREAD_POOL_SIZE, QUEUE_CAPACITY);
    }

    /** Cho test dat pool / hang doi nho de gia lap qua tai. */
    CacheServer(int port, KeyValueStore store, int threads, int queueCapacity) {
        this.port = port;
        this.store = store;
        this.handler = new ClientHandler(store);
        // Hang doi CO GIOI HAN: Executors.newFixedThreadPool dung hang doi vo han nen khong bao gio
        // tu choi -> E004 khong the xay ra, server qua tai chi cham dan roi treo.
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, "cache-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    public KeyValueStore store() {
        return store;
    }

    /** Gan Module 3: sau moi PUT/DELETE thanh cong se goi replicator. Goi truoc start(). */
    public void setReplicator(Replicator replicator) {
        handler.setReplicator(replicator);
    }

    /** Mo port va nhan ket noi; chan (block) cho toi khi shutdown(). */
    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            listening.countDown();
            LOGGER.info(() -> "CacheServer dang lang nghe tai port " + ss.getLocalPort());
            while (running) {
                Socket client;
                try {
                    client = ss.accept();
                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Loi accept: " + e.getMessage(), e);
                    }
                    continue;
                }
                try {
                    pool.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                    LOGGER.warning("Server qua tai, tu choi ket noi (E004)");
                    reply(client, ProtocolParser.error(ErrorCode.E004));
                }
            }
        } finally {
            shutdown();
        }
    }

    /** Doi toi khi server da mo port. Test cua cac module khac dung de bat server that. */
    public boolean awaitListening(long timeoutMs) throws InterruptedException {
        return listening.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Port thuc te dang lang nghe (khi tao voi port 0 thi he dieu hanh tu chon). */
    public int localPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            LineReader.Result line = LineReader.read(in, MAX_LINE_LENGTH);
            if (line.tooLong()) {
                write(socket, ProtocolParser.error(ErrorCode.E003));
                LineReader.drain(socket, in);
            } else if (line.text() != null) {
                requestCount.incrementAndGet();
                write(socket, handler.handle(line.text()));
            }
        } catch (SocketTimeoutException e) {
            LOGGER.fine("Dong ket noi im lang qua " + IDLE_TIMEOUT_MS + "ms");
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Loi ket noi client: " + e.getMessage(), e);
        }
    }

    /** So yeu cau da xu ly tren server nay (dung cho Registry heartbeat va giam sat). */
    public long requestCount() {
        return requestCount.get();
    }

    /** So key hien co trong bo nho dem (dung cho Registry heartbeat va giam sat). */
    public int keyCount() {
        return store.size();
    }

    private static void reply(Socket socket, String response) {
        try (socket) {
            write(socket, response);
        } catch (IOException e) {
            LOGGER.fine("Khong gui duoc phan hoi: " + e.getMessage());
        }
    }

    private static void write(Socket socket, String response) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Chay: java -jar target/keynet.jar [file-config] [serverIndex]
     * Mac dinh config/config.properties; serverIndex ghi de cache.serverIndex trong file,
     * de chay 3 server tu cung 1 file config.
     */
    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config/config.properties";
        ServerConfig config;
        try {
            config = ServerConfig.load(configPath);
            if (args.length > 1) {
                config = config.withServerIndex(Integer.parseInt(args[1]));
            }
        } catch (IOException e) {
            LOGGER.severe("Khong doc duoc file cau hinh " + configPath + ": " + e.getMessage());
            System.exit(1);
            return;
        } catch (IllegalArgumentException e) {
            LOGGER.severe("Cau hinh khong hop le: " + e.getMessage());
            System.exit(1);
            return;
        }

        ServerConfig.ServerAddress self = config.self();
        CacheServer server = new CacheServer(self.port(), config.ttlSeconds(), config.maxEntries());

        HeartbeatSender heartbeatSender = null;
        if (config.registry() != null) {
            ServerConfig.ServerAddress reg = config.registry();
            heartbeatSender = new HeartbeatSender(
                    reg.host(), reg.port(),
                    config.serverIndex(),
                    self.host(), self.port(),
                    server::keyCount,
                    server::requestCount
            );
            heartbeatSender.start();
        }
        final HeartbeatSender finalHeartbeat = heartbeatSender;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (finalHeartbeat != null) {
                finalHeartbeat.close();
            }
            server.shutdown();
        }, "cache-shutdown"));

        LOGGER.info("KeyNet CacheServer #" + config.serverIndex() + " - port " + self.port()
                + ", ttl=" + config.ttlSeconds() + "s, maxEntries=" + config.maxEntries());
        try {
            server.start();
        } catch (IOException e) {
            LOGGER.severe("Khong mo duoc port " + self.port() + " (dang bi chiem?): " + e.getMessage());
            System.exit(1);
        }
    }

    /** Dung server: dong ServerSocket (nha port ngay), dung thread pool va kho. */
    public void shutdown() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                LOGGER.fine("Loi dong ServerSocket: " + e.getMessage());
            }
        }
        pool.shutdownNow();
        store.close();
    }
}
