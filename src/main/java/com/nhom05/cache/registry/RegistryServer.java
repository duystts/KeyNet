package com.nhom05.cache.registry;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 4 — Monitoring / Registry + Health Check.
 * Phụ trách: Nguyễn Trần Tuấn Anh.
 *
 * Nhiệm vụ chính:
 * 1. Lắng nghe trên port cố định (mặc định: 6000 theo mục 6 đặc tả giao thức).
 * 2. Nhận HEARTBEAT định kỳ (mỗi 3 giây) từ các CacheServer và trả về ACK.
 * 3. Duyệt kiểm tra định kỳ (health check): nếu CacheServer không gửi heartbeat quá 9 giây,
 *    đánh dấu server đó là DOWN và ghi log cảnh báo phát hiện server chết.
 * 4. Khi server hồi phục và gửi lại heartbeat, tự động khôi phục trạng thái UP.
 * 5. Trả lời truy vấn STATUS|<serverIndex> (UP/DOWN) cho Client và STATS cho GUI dashboard.
 */
public class RegistryServer {

    private static final Logger LOGGER = Logger.getLogger(RegistryServer.class.getName());

    public static final int DEFAULT_PORT = 6000;
    public static final long HEARTBEAT_TIMEOUT_MS = 9000L; // 9 giay = 3 lan chu ky 3s
    public static final long HEALTH_CHECK_INTERVAL_MS = 1000L; // Quet moi 1 giay

    private final int port;
    private final long heartbeatTimeoutMs;
    private final ConcurrentHashMap<Integer, ServerNode> servers = new ConcurrentHashMap<>();

    private final ExecutorService clientHandlerPool = Executors.newFixedThreadPool(50);
    private final ScheduledExecutorService healthChecker = Executors.newSingleThreadScheduledExecutor();

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public RegistryServer() {
        this(DEFAULT_PORT, HEARTBEAT_TIMEOUT_MS);
    }

    public RegistryServer(int port) {
        this(port, HEARTBEAT_TIMEOUT_MS);
    }

    public RegistryServer(int port, long heartbeatTimeoutMs) {
        this.port = port;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    /**
     * Khoi dong RegistryServer va bat dau tien trinh quet Health Check.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        serverSocket = new ServerSocket(port);
        LOGGER.info(() -> "[REGISTRY] Server Registry khoi dong thanh cong tren port " + port
                + " (Timeout nguong: " + heartbeatTimeoutMs + "ms)");

        // Bat dau background thread quet Health Check dinh ky
        healthChecker.scheduleAtFixedRate(
                this::checkHealth,
                HEALTH_CHECK_INTERVAL_MS,
                HEALTH_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        // Lang nghe ket noi TCP
        Thread acceptThread = new Thread(this::listenLoop, "Registry-Accept-Thread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void listenLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientHandlerPool.execute(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "[REGISTRY] Loi khi chap nhan ket noi: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Xu ly ket noi tu CacheServer, Client hoac GUI Dashboard.
     */
    void handleConnection(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String response = handleCommand(line);
                writer.write(response);
                writer.write("\n");
                writer.flush();
            }
        } catch (IOException e) {
            LOGGER.fine(() -> "[REGISTRY] Ket noi dong hoac loi IO: " + e.getMessage());
        }
    }

    /**
     * Parse va xu ly tung dong lenh theo chuan dac ta:
     * - HEARTBEAT|<serverIndex>|<host>|<port>[|<keyCount>|<requestCount>] -> ACK
     * - STATUS|<serverIndex> -> UP hoac DOWN
     * - STATS -> STATS|<serverIndex>:<status>:<keyCount>:<requestCount>;...
     */
    public String handleCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return ProtocolParser.error(ErrorCode.E001, "Empty command");
        }

        String[] parts = rawCommand.split(ProtocolParser.DELIMITER, -1);
        String action = parts[0].trim().toUpperCase();

        return switch (action) {
            case "HEARTBEAT" -> handleHeartbeat(parts);
            case "STATUS" -> handleStatus(parts);
            case "STATS" -> handleStats();
            default -> ProtocolParser.error(ErrorCode.E001, "Unknown command: " + action);
        };
    }

    /**
     * Xu ly lenh HEARTBEAT|<serverIndex>|<host>|<port>
     * Ho tro ca dang mo rong co keyCount va requestCount.
     */
    private String handleHeartbeat(String[] parts) {
        if (parts.length < 4) {
            return ProtocolParser.error(ErrorCode.E001, "Invalid HEARTBEAT syntax. Expect: HEARTBEAT|<index>|<host>|<port>");
        }

        try {
            int serverIndex = Integer.parseInt(parts[1].trim());
            String host = parts[2].trim();
            int port = Integer.parseInt(parts[3].trim());

            int keyCount = 0;
            long requestCount = 0;
            if (parts.length >= 6) {
                try {
                    keyCount = Integer.parseInt(parts[4].trim());
                    requestCount = Long.parseLong(parts[5].trim());
                } catch (NumberFormatException ignored) {
                }
            }

            final int finalKeyCount = keyCount;
            final long finalReqCount = requestCount;

            servers.compute(serverIndex, (idx, existing) -> {
                if (existing == null) {
                    LOGGER.info(() -> "[REGISTRY-REGISTER] Phat hien Cache Server moi #" + idx
                            + " (" + host + ":" + port + ") dang hoat dong [UP]");
                    return new ServerNode(idx, host, port, finalKeyCount, finalReqCount);
                } else {
                    boolean wasDown = existing.getStatus() == ServerNode.Status.DOWN;
                    if (finalKeyCount > 0 || finalReqCount > 0) {
                        existing.recordHeartbeat(host, port, finalKeyCount, finalReqCount);
                    } else {
                        existing.recordHeartbeat(host, port);
                    }
                    if (wasDown) {
                        LOGGER.info(() -> "[SERVER RECOVERED] Cache Server #" + idx
                                + " (" + host + ":" + port + ") da hoat dong tro lai! Chuyen trang thai sang UP.");
                    }
                    return existing;
                }
            });

            return "ACK";
        } catch (NumberFormatException e) {
            return ProtocolParser.error(ErrorCode.E001, "Invalid number in HEARTBEAT command");
        }
    }

    /**
     * Xu ly lenh STATUS|<serverIndex>
     */
    private String handleStatus(String[] parts) {
        if (parts.length != 2) {
            return ProtocolParser.error(ErrorCode.E001, "Invalid STATUS syntax. Expect: STATUS|<serverIndex>");
        }

        try {
            int serverIndex = Integer.parseInt(parts[1].trim());
            ServerNode node = servers.get(serverIndex);
            if (node == null) {
                return "DOWN";
            }

            long now = System.currentTimeMillis();
            if (node.isAlive(now, heartbeatTimeoutMs)) {
                return "UP";
            } else {
                return "DOWN";
            }
        } catch (NumberFormatException e) {
            return ProtocolParser.error(ErrorCode.E001, "Invalid serverIndex in STATUS command");
        }
    }

    /**
     * Xu ly lenh STATS
     * Format theo muc 6 dac ta:
     * STATS|<serverIndex>:<UP|DOWN>:<so key>:<so request>;...
     */
    private String handleStats() {
        if (servers.isEmpty()) {
            return "STATS|";
        }

        long now = System.currentTimeMillis();
        List<ServerNode> sortedNodes = new ArrayList<>(servers.values());
        sortedNodes.sort(Comparator.comparingInt(ServerNode::getServerIndex));

        StringBuilder sb = new StringBuilder("STATS|");
        boolean first = true;
        for (ServerNode node : sortedNodes) {
            if (!first) {
                sb.append(";");
            }
            first = false;

            boolean alive = node.isAlive(now, heartbeatTimeoutMs);
            String statusStr = alive ? "UP" : "DOWN";

            sb.append(node.getServerIndex())
              .append(":")
              .append(statusStr)
              .append(":")
              .append(node.getKeyCount())
              .append(":")
              .append(node.getRequestCount());
        }

        return sb.toString();
    }

    /**
     * Tien trinh quet Health Check dinh ky:
     * Phat hien bat ky server nao khong gui heartbeat qua 9 giay (heartbeatTimeoutMs)
     * va ghi LOG CANH BAO SERVER CHET.
     */
    public void checkHealth() {
        long now = System.currentTimeMillis();
        for (ServerNode node : servers.values()) {
            if (node.getStatus() == ServerNode.Status.UP && !node.isAlive(now, heartbeatTimeoutMs)) {
                node.setStatus(ServerNode.Status.DOWN);
                long elapsed = now - node.getLastHeartbeatTimestamp();
                LOGGER.warning(() -> String.format(
                        "[DEAD SERVER DETECTED] Server #%d (%s:%d) KHONG gui heartbeat qua %dms (> nguong %dms). Chuyen trang thai sang DOWN!",
                        node.getServerIndex(),
                        node.getHost(),
                        node.getPort(),
                        elapsed,
                        heartbeatTimeoutMs
                ));
            }
        }
    }

    public ServerNode getServer(int serverIndex) {
        return servers.get(serverIndex);
    }

    public ConcurrentHashMap<Integer, ServerNode> getServers() {
        return servers;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Dung toan bo dich vu Registry, thread pool va background timer.
     */
    public synchronized void shutdown() {
        running = false;
        healthChecker.shutdownNow();
        clientHandlerPool.shutdownNow();
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        LOGGER.info("[REGISTRY] RegistryServer da dung.");
    }

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Port khong hop le, su dung port mac dinh: " + DEFAULT_PORT);
            }
        }

        RegistryServer registry = new RegistryServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(registry::shutdown));

        try {
            registry.start();
            System.out.println("=================================================================");
            System.out.println("  KEYNET - REGISTRY SERVER (MODULE 4: HEALTH CHECK & MONITORING)");
            System.out.println("  Phu trach: Nguyen Tran Tuan Anh");
            System.out.println("  Dang lang nghe tren port: " + port);
            System.out.println("  Nguong phat hien server chet: " + (HEARTBEAT_TIMEOUT_MS / 1000) + " giay");
            System.out.println("=================================================================");

            // Giu tien trinh luon song
            Thread.currentThread().join();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[REGISTRY] Khong the khoi dong RegistryServer: " + e.getMessage(), e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
