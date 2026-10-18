package com.nhom05.cache.replication;

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
    private static final String ERR_FORMAT = "ERROR|E001|";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 2000;

    /** Dia chi kenh replication cua server du phong. */
    private record Backup(String host, int port) {
    }

    private final KeyValueStore store;
    /** null = chua co server du phong (cum 1 server) -> khong gui di dau. */
    private volatile Backup backup;

    private final ExecutorService senderPool;
    private final ExecutorService listenerPool;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    /** @param store kho du lieu cua server nay (dung khi nhan replication) */
    public ReplicationHandler(KeyValueStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store khong duoc null");
        }
        this.store = store;
        this.senderPool = Executors.newFixedThreadPool(2, daemonFactory("repl-sender"));
        this.listenerPool = Executors.newCachedThreadPool(daemonFactory("repl-listener"));
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

    /** Dat server du phong (host, port replication); host null hoac port <= 0 = khong gui. */
    public void setBackup(String host, int replicationPort) {
        backup = host == null || replicationPort <= 0 ? null : new Backup(host, replicationPort);
    }

    /**
     * Gui REPLICATE|PUT|key|value|timestamp, tra ve ngay lap tuc.
     * timestamp la thoi diem ghi tren server chinh (#9 dung de so sanh ban nao moi hon).
     */
    public void replicatePut(String key, String value, long timestamp) {
        send(ProtocolParser.replicatePut(key, value, timestamp));
    }

    /** Gui REPLICATE|DELETE|key|timestamp, tra ve ngay lap tuc. */
    public void replicateDelete(String key, long timestamp) {
        send(ProtocolParser.replicateDelete(key, timestamp));
    }

    /** Dua viec gui vao thread pool rieng; moi loi deu duoc nuot lai. */
    private void send(String line) {
        Backup target = backup;
        if (target == null) {
            return; // khong co doi tac
        }
        try {
            senderPool.execute(() -> sendBlocking(target, line));
        } catch (RuntimeException e) { // pool da dong
            LOGGER.log(Level.WARNING, "Khong dua duoc replication vao hang doi: {0}", e.toString());
        }
    }

    /** Gui 1 lan duy nhat, doc phan hoi xong moi dong socket. Khong retry. */
    private void sendBlocking(Backup target, String line) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), CONNECT_TIMEOUT_MS);
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
                    new Object[] { target.host(), target.port(), e.toString() });
        }
    }

    // ------------------------------------------------------------------
    // Phia nhan (listener)
    // ------------------------------------------------------------------

    /**
     * Mo cong lang nghe replication. Truyen 0 de he dieu hanh chon cong trong (dung
     * cho test), xem cong that bang listenerPort().
     */
    public synchronized void startListener(int port) throws IOException {
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
    }

    /** Cong dang lang nghe, -1 neu chua mo. */
    public int listenerPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
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
     * Ap dung 1 lenh replication vao kho (ghi truc tiep qua KeyValueStore nen TTL
     * va LRU van hoat dong nhu thuong). Tra ve "OK" hoac "ERROR|E001|...".
     * Timestamp moi duoc kiem tra la so; so sanh ban moi/cu lam o #9.
     */
    String apply(String line) {
        ProtocolParser.Replicate r = ProtocolParser.parseReplicate(line);
        if (r == null) {
            return ERR_FORMAT + "can REPLICATE|PUT|key|value|timestamp hoac REPLICATE|DELETE|key|timestamp";
        }
        if (!ProtocolParser.isKeyValid(r.key()) || (r.isPut() && !ProtocolParser.isValueValid(r.value()))) {
            return ERR_FORMAT + "key/value khong hop le";
        }
        if (r.isPut()) {
            store.putReplicated(r.key(), r.value(), r.timestamp());
        } else {
            store.deleteReplicated(r.key(), r.timestamp());
        }
        return OK;
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
