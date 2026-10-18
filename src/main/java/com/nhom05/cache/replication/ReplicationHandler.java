package com.nhom05.cache.replication;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.server.KeyValueStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Xu ly replication giua Primary va Backup (cong replication = cong client +
 * 1000).
 *
 * Giao thuc (moi ban tin 1 dong, ket thuc bang xuong dong):
 * REPLICATE|PUT|key|value|timestamp
 * REPLICATE|DELETE|key|timestamp
 * Backup tra ve "OK" hoac "ERROR|E001|mo ta" neu ban tin sai.
 *
 * Phia gui: chay tren thread pool rieng, khong bao gio chan thread tra loi
 * client,
 * loi ket noi chi log WARNING, khong retry, khong nem ngoai le.
 * Phia nhan: ServerSocket lang nghe, moi lenh goi apply(line) cap nhat
 * KeyValueStore.
 */
public final class ReplicationHandler implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ReplicationHandler.class.getName());

    private static final String OK = "OK";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 2000;

    private final KeyValueStore store;
    private volatile String peerHost;
    private volatile int peerPort;

    private final ExecutorService senderPool;
    private final ExecutorService listenerPool;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public ReplicationHandler(KeyValueStore store) {
        this(store, null, 0);
    }

    /**
     * @param store    kho du lieu cua server nay (dung khi nhan replication)
     * @param peerHost host cua server doi tac; null neu khong gui di dau
     * @param peerPort cong replication cua doi tac; &lt;= 0 neu khong gui di dau
     */
    public ReplicationHandler(KeyValueStore store, String peerHost, int peerPort) {
        if (store == null) {
            throw new IllegalArgumentException("store khong duoc null");
        }
        this.store = store;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.senderPool = Executors.newFixedThreadPool(2, daemonFactory("repl-sender"));
        this.listenerPool = Executors.newCachedThreadPool(daemonFactory("repl-listener"));
    }

    /** Cap nhat dia chi server du phong de gui ban tin replication. */
    public void setBackup(String host, int replicationPort) {
        this.peerHost = host;
        this.peerPort = replicationPort;
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------
    // Phia gui (bat dong bo)
    // ------------------------------------------------------------------

    /** Gui REPLICATE|PUT|key|value|timestamp, tra ve ngay lap tuc. */
    public void replicatePut(String key, String value) {
        replicatePut(key, value, System.currentTimeMillis());
    }

    public void replicatePut(String key, String value, long timestamp) {
        if (!ProtocolParser.isKeyValid(key) || !ProtocolParser.isValueValid(value)) {
            return;
        }
        send(ProtocolParser.replicatePut(key, value, timestamp));
    }

    /** Gui REPLICATE|DELETE|key|timestamp, tra ve ngay lap tuc. */
    public void replicateDelete(String key) {
        replicateDelete(key, System.currentTimeMillis());
    }

    public void replicateDelete(String key, long timestamp) {
        if (!ProtocolParser.isKeyValid(key)) {
            return;
        }
        send(ProtocolParser.replicateDelete(key, timestamp));
    }

    /** Dua viec gui vao thread pool rieng; moi loi deu duoc nuot lai. */
    private void send(String line) {
        String host = this.peerHost;
        int port = this.peerPort;
        if (host == null || port <= 0) {
            return; // khong co doi tac
        }
        try {
            senderPool.execute(() -> sendBlocking(line, host, port));
        } catch (RuntimeException e) { // pool da dong
            LOGGER.log(Level.WARNING, "Khong dua duoc replication vao hang doi: {0}", e.toString());
        }
    }

    /** Gui 1 lan duy nhat, doc phan hoi xong moi dong socket. Khong retry. */
    private void sendBlocking(String line, String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.print(line + "\n");
            out.flush();
            String reply = in.readLine(); // phai doc xong phan hoi roi moi dong
            if (!OK.equals(reply)) {
                LOGGER.log(Level.WARNING, "Backup phan hoi khong phai OK: {0}", reply);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Replication toi {0}:{1} that bai: {2}",
                    new Object[] { host, port, e.toString() });
        }
    }

    // ------------------------------------------------------------------
    // Phia nhan (listener)
    // ------------------------------------------------------------------

    /**
     * Mo cong lang nghe replication. Truyen 0 de he dieu hanh chon cong trong (dung
     * cho test).
     * 
     * @return cong thuc te dang lang nghe
     */
    public synchronized int startListener(int port) throws IOException {
        if (running) {
            throw new IllegalStateException("Listener da chay");
        }
        ServerSocket ss = new ServerSocket(port);
        serverSocket = ss;
        running = true;
        Thread acceptor = new Thread(() -> acceptLoop(ss), "repl-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        LOGGER.log(Level.INFO, "Replication listener lang nghe cong {0}", ss.getLocalPort());
        return ss.getLocalPort();
    }

    /** Cong dang lang nghe, -1 neu chua mo. */
    public int listenerPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    public int getListenPort() {
        return listenerPort();
    }

    private void acceptLoop(ServerSocket ss) {
        while (running && !ss.isClosed()) {
            try {
                Socket client = ss.accept();
                listenerPool.execute(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "Loi accept replication: {0}", e.toString());
                }
            } catch (RuntimeException e) {
                return; // pool da dong
            }
        }
    }

    private void handleConnection(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
            String line;
            while ((line = in.readLine()) != null) {
                out.print(apply(line) + "\n");
                out.flush();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Loi xu ly ket noi replication: {0}", e.toString());
        }
    }

    /**
     * Ap dung 1 lenh replication vao kho (ghi qua putReplicated/deleteReplicated de
     * ap dung LWW, tombstone, TTL va LRU). Tra ve "OK" hoac "ERROR|ma loi|...".
     */
    String apply(String line) {
        if (line == null || line.isBlank()) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        String[] parts = line.strip().split(ProtocolParser.DELIMITER, -1);
        if (parts.length < 4 || !"REPLICATE".equals(parts[0])) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        String subCmd = parts[1];
        if ("PUT".equals(subCmd)) {
            if (parts.length != 5) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            String key = parts[2];
            String val = parts[3];
            long ts;
            try {
                ts = Long.parseLong(parts[4]);
                if (ts < 0) {
                    return ProtocolParser.error(ErrorCode.E001);
                }
            } catch (NumberFormatException e) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            if (key.length() > ProtocolParser.MAX_KEY_LENGTH) {
                return ProtocolParser.error(ErrorCode.E002);
            }
            if (!ProtocolParser.isKeyValid(key)) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            if (val.length() > ProtocolParser.MAX_VALUE_LENGTH) {
                return ProtocolParser.error(ErrorCode.E003);
            }
            if (!ProtocolParser.isValueValid(val)) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            store.putReplicated(key, val, ts);
            return OK;
        } else if ("DELETE".equals(subCmd)) {
            if (parts.length != 4) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            String key = parts[2];
            long ts;
            try {
                ts = Long.parseLong(parts[3]);
                if (ts < 0) {
                    return ProtocolParser.error(ErrorCode.E001);
                }
            } catch (NumberFormatException e) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            if (key.length() > ProtocolParser.MAX_KEY_LENGTH) {
                return ProtocolParser.error(ErrorCode.E002);
            }
            if (!ProtocolParser.isKeyValid(key)) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            store.deleteReplicated(key, ts);
            return OK;
        }
        return ProtocolParser.error(ErrorCode.E001);
    }

    /** Dong listener va cac thread pool. */
    @Override
    public synchronized void close() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // bo qua khi dong
            }
        }
        senderPool.shutdown();
        listenerPool.shutdownNow();
        try {
            senderPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
