package com.nhom05.cache.server;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
 * doc 1 dong request -> tra 1 dong response (muc 1, 8 dac ta). Client keep-alive (#58) gui tiep
 * trong 1s thi dung lai ket noi; im lang hoac hang doi dang co ket noi cho thi dong.
 */
public final class CacheServer {

    private static final Logger LOGGER = Logger.getLogger(CacheServer.class.getName());

    static final int THREAD_POOL_SIZE = 100;
    /** So ket noi toi da duoc xep hang cho thread ranh; vuot qua thi tra E004. */
    static final int QUEUE_CAPACITY = 500;
    /** Hang doi ket noi cho ServerSocket (backlog). */
    static final int SERVER_BACKLOG = 1024;
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
        try (ServerSocket ss = new ServerSocket(port, SERVER_BACKLOG)) {
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
            socket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            boolean first = true;
            while (running && !socket.isClosed()) {
                if (!first) {
                    // Neu da xu ly it nhat 1 request va hang doi dang co ket noi khac cho thi nhuong luong
                    if (!pool.getQueue().isEmpty()) {
                        break;
                    }
                    socket.setSoTimeout(1000);
                }

                LineReader.Result line;
                try {
                    line = LineReader.read(in, MAX_LINE_LENGTH);
                } catch (SocketTimeoutException e) {
                    if (first) {
                        LOGGER.fine("Dong ket noi im lang qua " + IDLE_TIMEOUT_MS + "ms");
                    }
                    break;
                } catch (IOException e) {
                    break;
                }

                if (line.tooLong()) {
                    write(socket, ProtocolParser.error(ErrorCode.E003));
                    LineReader.drain(socket, in);
                    break;
                } else if (line.text() != null) {
                    requestCount.incrementAndGet();
                    write(socket, handler.handle(line.text()));
                    first = false;
                } else {
                    // Client da dong ket noi (EOF)
                    break;
                }
            }
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

    /**
     * Chay tren thread accept nen khong duoc doi client: gui response, dong chieu ghi (FIN de
     * client doc het response), bo qua phan request da toi san roi dong. Khong doc chan (block)
     * vi moi ket noi bi tu choi se giu thread accept, ket noi sau phai cho rat lau moi nhan E004.
     */
    private static void reply(Socket socket, String response) {
        try (socket) {
            write(socket, response);
            socket.shutdownOutput();
            InputStream in = socket.getInputStream();
            in.skip(in.available());
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

        CacheNode node = new CacheNode(config);
        // Nut "Sap node" cua GUI: dung han nhu mat nguon (halt bo qua shutdown hook),
        // bat lai thi server tu lay lai du lieu qua recover
        node.setShutdownAction(() -> Runtime.getRuntime().halt(0));
        Runtime.getRuntime().addShutdownHook(new Thread(node::close, "cache-shutdown"));
        try {
            node.start();
        } catch (IOException e) {
            LOGGER.severe(e.getMessage() + " (port dang bi chiem?)");
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
