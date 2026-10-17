package com.nhom05.cache.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Module 4 - Client ket noi toi RegistryServer de gui heartbeat hoac truy van
 * trang thai song/chet va so lieu thong ke cua cac Cache Server trong cum.
 */
public final class RegistryClient {

    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private final String host;
    private final int port;
    private final int timeoutMs;

    public RegistryClient(String host, int port) {
        this(host, port, DEFAULT_TIMEOUT_MS);
    }

    public RegistryClient(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host khong duoc de trong");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port khong hop le: " + port);
        }
        this.host = host.strip();
        this.port = port;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /**
     * Gui HEARTBEAT co kem so lieu thong ke (keyCount, requestCount).
     * Tra ve true neu Registry phan hoi ACK, false neu xay ra loi mang hoac Registry tu choi.
     */
    public boolean sendHeartbeat(int index, String host, int port, int keyCount, long requestCount) {
        String line = "HEARTBEAT|" + index + "|" + host + "|" + port + "|" + keyCount + "|" + requestCount;
        try {
            String response = execute(line);
            return "ACK".equals(response);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Gui HEARTBEAT khong kem so lieu thong ke.
     */
    public boolean sendHeartbeat(int index, String host, int port) {
        String line = "HEARTBEAT|" + index + "|" + host + "|" + port;
        try {
            String response = execute(line);
            return "ACK".equals(response);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Truy van trang thai cua server theo serverIndex.
     * Tra ve "UP" neu server dang hoat dong, "DOWN" neu server chet / chua tung heartbeat,
     * hoac null neu KHONG ket noi duoc toi Registry (GUI dung de hien thi thong bao phu hop).
     */
    public String status(int index) {
        try {
            String resp = execute("STATUS|" + index);
            if (resp == null) {
                return null;
            }
            String trimmed = resp.strip();
            if ("UP".equalsIgnoreCase(trimmed)) {
                return "UP";
            }
            if ("DOWN".equalsIgnoreCase(trimmed)) {
                return "DOWN";
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * So lieu thong ke cua 1 node trong cum.
     */
    public record NodeStat(int index, boolean up, int keyCount, long requestCount) {
    }

    /**
     * Lay danh sach thong ke cua toan bo cac server trong cum.
     * Neu Registry khong chay hoac xay ra loi ket noi, tra ve danh sach rong.
     */
    public List<NodeStat> stats() {
        try {
            String resp = execute("STATS");
            if (resp == null || !resp.startsWith("STATS|")) {
                return List.of();
            }
            String payload = resp.substring("STATS|".length()).strip();
            if (payload.isEmpty()) {
                return List.of();
            }
            String[] rawNodes = payload.split(";");
            List<NodeStat> list = new ArrayList<>(rawNodes.length);
            for (String raw : rawNodes) {
                if (raw.isBlank()) {
                    continue;
                }
                String[] parts = raw.split(":");
                if (parts.length == 4) {
                    int idx = Integer.parseInt(parts[0]);
                    boolean up = "UP".equalsIgnoreCase(parts[1]);
                    int kc = Integer.parseInt(parts[2]);
                    long rc = Long.parseLong(parts[3]);
                    list.add(new NodeStat(idx, up, kc, rc));
                }
            }
            return List.copyOf(list);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String execute(String line) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        }
    }
}
