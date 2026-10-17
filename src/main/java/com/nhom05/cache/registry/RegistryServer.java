package com.nhom05.cache.registry;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 4 - Registry Server: theo doi trang thai song/chet cua cac Cache Server trong cum.
 * Nhan HEARTBEAT dinh ky, tra ve STATUS va STATS cho Client va Dashboard GUI.
 */
public final class RegistryServer {

    private static final Logger LOGGER = Logger.getLogger(RegistryServer.class.getName());

    public static final int DEFAULT_PORT = 6000;
    public static final long TIMEOUT_MS = 9000;          // 3 chu ky heartbeat
    public static final int IDLE_TIMEOUT_MS = 60_000;    // 60 giay cho ket noi im lang

    static final int THREAD_POOL_SIZE = 20;
    static final int QUEUE_CAPACITY = 100;

    static final class NodeInfo {
        final int index;
        volatile String host;
        volatile int port;
        volatile int keyCount;
        volatile long requestCount;
        volatile long lastHeartbeat;
        volatile boolean up;

        NodeInfo(int index, String host, int port, int keyCount, long requestCount,
                 long lastHeartbeat, boolean up) {
            this.index = index;
            this.host = host;
            this.port = port;
            this.keyCount = keyCount;
            this.requestCount = requestCount;
            this.lastHeartbeat = lastHeartbeat;
            this.up = up;
        }

        boolean isUp(long now, long timeoutMs) {
            return up && (now - lastHeartbeat <= timeoutMs);
        }
    }

    private final int port;
    private final long timeoutMs;
    private final ConcurrentHashMap<Integer, NodeInfo> nodes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor clientPool;
    private final ScheduledExecutorService healthChecker;
    private final CountDownLatch listening = new CountDownLatch(1);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;
    private Thread acceptThread;

    public RegistryServer(int port) {
        this(port, TIMEOUT_MS);
    }

    public RegistryServer(int port, long timeoutMs) {
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.clientPool = new ThreadPoolExecutor(
                THREAD_POOL_SIZE, THREAD_POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                    Thread t = new Thread(r, "registry-worker");
                    t.setDaemon(true);
                    return t;
                });
        this.healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "registry-health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Mo port roi tra ve ngay, viec accept client chay tren thread nen.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        listening.countDown();
        LOGGER.info(() -> "RegistryServer dang lang nghe tai port " + serverSocket.getLocalPort());

        acceptThread = new Thread(this::runAcceptLoop, "registry-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        long periodMs = Math.min(1000, Math.max(50, timeoutMs / 3));
        healthChecker.scheduleAtFixedRate(this::checkNodesHealth, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Port thuc te dang lang nghe (khi tao voi port 0 thi he dieu hanh tu chon).
     */
    public int localPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    /**
     * Cho phep test cho toi khi server da san sang lang nghe.
     */
    public boolean awaitListening(long timeoutMs) throws InterruptedException {
        return listening.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Dung tien trinh main cho toi khi server shutdown.
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    /**
     * Dong server, giai phong port va tat cac thread pool.
     */
    public void shutdown() {
        running = false;
        shutdownLatch.countDown();
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            try {
                ss.close();
            } catch (IOException e) {
                LOGGER.fine(() -> "Loi khi dong ServerSocket: " + e.getMessage());
            }
        }
        healthChecker.shutdownNow();
        clientPool.shutdownNow();
    }

    private void runAcceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                clientPool.execute(() -> serve(client));
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "Loi accept tren RegistryServer: " + e.getMessage(), e);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handleCommand(line);
                out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (SocketTimeoutException e) {
            LOGGER.fine(() -> "Dong ket noi im lang qua " + IDLE_TIMEOUT_MS + "ms");
        } catch (IOException e) {
            if (running) {
                LOGGER.log(Level.FINE, () -> "Loi ket noi client: " + e.getMessage());
            }
        }
    }

    /**
     * Xu ly 1 dong lenh, tra ve 1 dong phan hoi (chua co '\n').
     * Package-private de phuc vu test khong can thiet lap socket.
     */
    String handleCommand(String line) {
        if (line == null || line.isBlank()) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        String stripped = line.strip();
        String[] parts = stripped.split(ProtocolParser.DELIMITER, -1);
        String cmd = parts[0].toUpperCase();
        return switch (cmd) {
            case "HEARTBEAT" -> handleHeartbeat(parts);
            case "STATUS" -> handleStatus(parts);
            case "STATS" -> handleStats(parts);
            default -> ProtocolParser.error(ErrorCode.E001);
        };
    }

    private String handleHeartbeat(String[] parts) {
        if (parts.length != 4 && parts.length != 6) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        try {
            int index = Integer.parseInt(parts[1]);
            String host = parts[2].strip();
            int port = Integer.parseInt(parts[3]);
            if (index < 0 || host.isEmpty() || port <= 0 || port > 65535) {
                return ProtocolParser.error(ErrorCode.E001);
            }
            int keyCount = 0;
            long requestCount = 0;
            boolean hasStats = (parts.length == 6);
            if (hasStats) {
                keyCount = Integer.parseInt(parts[4]);
                requestCount = Long.parseLong(parts[5]);
                if (keyCount < 0 || requestCount < 0) {
                    return ProtocolParser.error(ErrorCode.E001);
                }
            }
            final int finalKeyCount = keyCount;
            final long finalRequestCount = requestCount;
            long now = System.currentTimeMillis();

            nodes.compute(index, (idx, existing) -> {
                if (existing == null) {
                    LOGGER.info(() -> "Server #" + index + " dang ky lan dau (" + host + ":" + port + ")");
                    return new NodeInfo(index, host, port, finalKeyCount, finalRequestCount, now, true);
                }
                if (!existing.isUp(now, timeoutMs)) {
                    LOGGER.info("[SERVER RECOVERED] Server #" + index + " (" + host + ":" + port + ") da hoat dong tro lai");
                }
                existing.host = host;
                existing.port = port;
                existing.lastHeartbeat = now;
                existing.up = true;
                if (hasStats) {
                    existing.keyCount = finalKeyCount;
                    existing.requestCount = finalRequestCount;
                }
                return existing;
            });
            return "ACK";
        } catch (NumberFormatException e) {
            return ProtocolParser.error(ErrorCode.E001);
        }
    }

    private String handleStatus(String[] parts) {
        if (parts.length != 2) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        try {
            int index = Integer.parseInt(parts[1]);
            NodeInfo node = nodes.get(index);
            if (node == null) {
                return "DOWN";
            }
            long now = System.currentTimeMillis();
            return node.isUp(now, timeoutMs) ? "UP" : "DOWN";
        } catch (NumberFormatException e) {
            return ProtocolParser.error(ErrorCode.E001);
        }
    }

    private String handleStats(String[] parts) {
        if (parts.length != 1) {
            return ProtocolParser.error(ErrorCode.E001);
        }
        if (nodes.isEmpty()) {
            return "STATS|";
        }
        long now = System.currentTimeMillis();
        List<NodeInfo> sorted = new ArrayList<>(nodes.values());
        sorted.sort(Comparator.comparingInt(n -> n.index));
        StringBuilder sb = new StringBuilder("STATS|");
        boolean first = true;
        for (NodeInfo n : sorted) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            String status = n.isUp(now, timeoutMs) ? "UP" : "DOWN";
            sb.append(n.index).append(':')
                    .append(status).append(':')
                    .append(n.keyCount).append(':')
                    .append(n.requestCount);
        }
        return sb.toString();
    }

    private void checkNodesHealth() {
        long now = System.currentTimeMillis();
        for (NodeInfo node : nodes.values()) {
            if (node.up && (now - node.lastHeartbeat > timeoutMs)) {
                node.up = false;
                LOGGER.warning("[DEAD SERVER DETECTED] Server #" + node.index + " (" + node.host + ":" + node.port
                        + ") khong gui heartbeat qua " + timeoutMs + "ms");
            }
        }
    }

    /**
     * Chay: java com.nhom05.cache.registry.RegistryServer [port]
     * Mac dinh port 6000.
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOGGER.severe("Port khong hop le: " + args[0]);
                System.exit(1);
                return;
            }
        }
        RegistryServer server = new RegistryServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "registry-shutdown"));
        try {
            server.start();
            LOGGER.info("RegistryServer dang lang nghe tai port " + server.localPort());
            server.awaitShutdown();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOGGER.severe("Khong mo duoc port " + port + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
