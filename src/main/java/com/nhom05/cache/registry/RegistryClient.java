package com.nhom05.cache.registry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Client ket noi toi RegistryServer (port 6000) danh cho:
 * - Module 2 (Client): Truy van STATUS|<serverIndex> de biet server UP/DOWN truoc hoac trong khi failover.
 * - Module 5 (GUI): Truy van STATS de lay dashboard tong quan he thong.
 */
public class RegistryClient {

    private static final Logger LOGGER = Logger.getLogger(RegistryClient.class.getName());

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = RegistryServer.DEFAULT_PORT;
    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private final String registryHost;
    private final int registryPort;
    private final int timeoutMs;

    public record ServerStatRecord(int serverIndex, ServerNode.Status status, int keyCount, long requestCount) {
    }

    public RegistryClient() {
        this(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_TIMEOUT_MS);
    }

    public RegistryClient(String host, int port) {
        this(host, port, DEFAULT_TIMEOUT_MS);
    }

    public RegistryClient(String host, int port, int timeoutMs) {
        this.registryHost = host;
        this.registryPort = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Kiem tra serverIndex co dang UP hay khong.
     * Tra ve true neu Registry phan hoi "UP", false neu "DOWN" hoac khong ket noi duoc Registry.
     */
    public boolean isServerUp(int serverIndex) {
        String res = checkStatus(serverIndex);
        return "UP".equalsIgnoreCase(res);
    }

    /**
     * Gui lenh STATUS|<serverIndex> toi Registry.
     * Tra ve "UP" hoac "DOWN".
     */
    public String checkStatus(int serverIndex) {
        try {
            String response = sendCommand("STATUS|" + serverIndex);
            if (response != null && (response.equalsIgnoreCase("UP") || response.equalsIgnoreCase("DOWN"))) {
                return response.toUpperCase();
            }
        } catch (IOException e) {
            LOGGER.warning(() -> "[REGISTRY-CLIENT] Khong the ket noi toi Registry tai "
                    + registryHost + ":" + registryPort + " - " + e.getMessage());
        }
        return "DOWN";
    }

    /**
     * Gui lenh STATS toi Registry de lay chuoi thong ke cua tat ca server.
     */
    public String getRawStats() {
        try {
            return sendCommand("STATS");
        } catch (IOException e) {
            LOGGER.warning(() -> "[REGISTRY-CLIENT] Loi khi lay STATS tu Registry: " + e.getMessage());
            return "STATS|";
        }
    }

    /**
     * Parse chuoi STATS thanh danh sach ServerStatRecord phuc vu cho GUI dashboard.
     */
    public List<ServerStatRecord> getParsedStats() {
        List<ServerStatRecord> result = new ArrayList<>();
        String raw = getRawStats();
        if (raw == null || !raw.startsWith("STATS|")) {
            return result;
        }

        String data = raw.substring(6).trim();
        if (data.isEmpty()) {
            return result;
        }

        String[] serverEntries = data.split(";");
        for (String entry : serverEntries) {
            if (entry.isBlank()) continue;
            String[] tokens = entry.split(":");
            if (tokens.length >= 4) {
                try {
                    int idx = Integer.parseInt(tokens[0]);
                    ServerNode.Status status = "UP".equalsIgnoreCase(tokens[1]) ? ServerNode.Status.UP : ServerNode.Status.DOWN;
                    int keyCount = Integer.parseInt(tokens[2]);
                    long reqCount = Long.parseLong(tokens[3]);
                    result.add(new ServerStatRecord(idx, status, keyCount, reqCount));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    /**
     * Gui lenh HEARTBEAT tu Cache Server toi Registry.
     */
    public boolean sendHeartbeat(int serverIndex, String host, int port) {
        return sendHeartbeat(serverIndex, host, port, 0, 0);
    }

    public boolean sendHeartbeat(int serverIndex, String host, int port, int keyCount, long requestCount) {
        try {
            String cmd = "HEARTBEAT|" + serverIndex + "|" + host + "|" + port + "|" + keyCount + "|" + requestCount;
            String res = sendCommand(cmd);
            return "ACK".equalsIgnoreCase(res);
        } catch (IOException e) {
            return false;
        }
    }

    private String sendCommand(String commandLine) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(registryHost, registryPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.write(commandLine);
            writer.write("\n");
            writer.flush();

            return reader.readLine();
        }
    }
}
