package com.nhom05.cache.replication;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ProtocolParser.ParsedReplicate;
import com.nhom05.cache.server.KeyValueStore;
import com.nhom05.cache.server.Replicator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 3 - Replication &amp; Consistency (muc 5 dac ta giao thuc).
 *
 * Hai vai tro:
 *  1) Gui: server chinh goi replicatePut/replicateDelete sau moi PUT/DELETE thanh cong;
 *     lenh duoc gui BAT DONG BO toi server du phong ((serverIndex+1) mod N) tren port
 *     replication rieng. Loi gui chi ghi log, khong retry vo han, khong anh huong client.
 *  2) Nhan: listener tren port replication, ap dung lenh vao KeyValueStore qua
 *     putReplicated/deleteReplicated (timestamp lon hon thang).
 */
public final class ReplicationHandler implements Replicator {

    private static final Logger LOGGER = Logger.getLogger(ReplicationHandler.class.getName());
    private static final int TIMEOUT_MS = 2000;

    private final KeyValueStore store;
    private final ExecutorService sender = Executors.newFixedThreadPool(4, daemon("repl-sender"));
    private final ExecutorService workers = Executors.newCachedThreadPool(daemon("repl-worker"));

    private volatile String backupHost;
    private volatile int backupPort;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;

    public ReplicationHandler(KeyValueStore store) {
        this.store = store;
    }

    /** Cau hinh server du phong (host + port replication cua server (serverIndex+1) mod N). */
    public void configureBackupServer(String host, int replicationPort) {
        this.backupHost = host;
        this.backupPort = replicationPort;
        LOGGER.info(() -> "Server du phong: " + host + ":" + replicationPort);
    }

    // ---------------------------------------------------------------- Gui

    @Override
    public void replicatePut(String key, String value, long timestamp) {
        sendAsync("REPLICATE|PUT|" + key + "|" + value + "|" + timestamp);
    }

    @Override
    public void replicateDelete(String key, long timestamp) {
        sendAsync("REPLICATE|DELETE|" + key + "|" + timestamp);
    }

    private void sendAsync(String line) {
        String host = backupHost;
        int port = backupPort;
        if (host == null || port == 0) {
            LOGGER.warning("Chua cau hinh server du phong, bo qua replication.");
            return;
        }
        try {
            sender.execute(() -> sendOnce(host, port, line));
        } catch (RejectedExecutionException e) {
            LOGGER.warning("Replication da dong, bo qua lenh.");
        }
    }

    private void sendOnce(String host, int port, String line) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Doc phan hoi cua server du phong (khong de dong socket truoc khi no kip tra loi)
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = in.readLine();
            if (!ProtocolParser.ok().equals(reply)) {
                LOGGER.warning(() -> "Server du phong tu choi lenh dong bo: " + reply);
            }
        } catch (IOException e) {
            LOGGER.warning(() -> "Khong dong bo duoc toi " + host + ":" + port + " - " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Nhan

    /** Mo listener nhan lenh REPLICATE tren port replication cua server nay. */
    public void startListener(int replicationPort) {
        try {
            serverSocket = new ServerSocket(replicationPort);
        } catch (IOException e) {
            throw new IllegalStateException("Khong mo duoc port replication " + replicationPort, e);
        }
        Thread acceptor = new Thread(this::acceptLoop, "repl-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        LOGGER.info(() -> "Replication listener dang lang nghe tai port " + replicationPort);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "Loi accept replication: " + e.getMessage(), e);
                }
            } catch (RejectedExecutionException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(TIMEOUT_MS);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String response = apply(in.readLine());
            OutputStream out = socket.getOutputStream();
            out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Loi xu ly ket noi replication: " + e.getMessage());
        }
    }

    /** Ap dung 1 dong REPLICATE vao kho; tra ve response (OK hoac ERROR|E001|...). Tach rieng de test. */
    String apply(String line) {
        ParsedReplicate req = ProtocolParser.parseReplicate(line);
        if (req == null) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        try {
            if (req.isPut()) {
                store.putReplicated(req.key(), req.value(), req.timestamp());
            } else {
                store.deleteReplicated(req.key(), req.timestamp());
            }
            return ProtocolParser.ok();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Loi noi bo khi dong bo: " + e.getMessage(), e);
            return ProtocolParser.error(ErrorCode.E005);
        }
    }

    @Override
    public void close() {
        running = false;
        sender.shutdown();
        workers.shutdown();
        try {
            ServerSocket s = serverSocket;
            if (s != null) {
                s.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Loi dong port replication: " + e.getMessage());
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
