package com.nhom05.cache.replication;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.nhom05.cache.server.CacheEntry;

public class ReplicationHandler {

    private String backupServerIp;
    private int backupServerPort;
    private final ExecutorService executorService;
    private ExecutorService listenerPool;
    private ServerSocket serverSocket;

    public ReplicationHandler() {
        // Sử dụng một thread pool để gửi các lệnh bất đồng bộ
        this.executorService = Executors.newFixedThreadPool(4); 
    }

    /**
     * Cấu hình thông tin server dự phòng (được tính bằng (serverIndex + 1) mod N)
     *
     * @param ip IP của server dự phòng
     * @param port Cổng của server dự phòng
     */
    public void configureBackupServer(String ip, int port) {
        this.backupServerIp = ip;
        this.backupServerPort = port;
        System.out.println("Configured backup server: " + ip + ":" + port);
    }

    /**
     * Gửi lệnh đồng bộ PUT tới server dự phòng
     *
     * @param key Khóa cần lưu
     * @param value Giá trị cần lưu
     * @param timestamp Thời gian tạo/cập nhật
     */
    public void sendReplicatePut(String key, String value, long timestamp) {
        String command = String.format("REPLICATE|PUT|%s|%s|%d\n", key, value, timestamp);
        sendCommandAsync(command);
    }

    /**
     * Gửi lệnh đồng bộ DELETE tới server dự phòng
     *
     * @param key Khóa cần xóa
     * @param timestamp Thời gian tạo/cập nhật
     */
    public void sendReplicateDel(String key, long timestamp) {
        String command = String.format("REPLICATE|DELETE|%s|%d\n", key, timestamp);
        sendCommandAsync(command);
    }

    /**
     * Gửi lệnh qua TCP Socket một cách bất đồng bộ
     */
    private void sendCommandAsync(String command) {
        if (backupServerIp == null || backupServerPort == 0) {
            System.err.println("Warning: Backup server is not configured. Cannot replicate.");
            return;
        }

        executorService.submit(() -> {
            // Sử dụng try-with-resources để đảm bảo Socket tự động đóng
            try (Socket socket = new Socket()) {
                // Timeout kết nối và read/write: 2 giây (2000 ms)
                socket.connect(new InetSocketAddress(backupServerIp, backupServerPort), 2000);
                socket.setSoTimeout(2000);

                // Gửi dữ liệu dưới dạng UTF-8
                try (PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    out.print(command);
                    out.flush();
                }
            } catch (Exception e) {
                // Chỉ ghi log cảnh báo, không ném exception làm sập server chính, không retry vô hạn
                System.err.println("Warning: Failed to replicate to backup server " + backupServerIp + ":" + backupServerPort + " - " + e.getMessage());
            }
        });
    }

    /**
     * Bắt đầu Server Socket (Listener) để nhận dữ liệu đồng bộ
     */
    public void startReplicationListener(int replicationPort, ConcurrentHashMap<String, CacheEntry> datastore) {
        listenerPool = Executors.newCachedThreadPool();
        Thread listenerThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(replicationPort);
                System.out.println("Replication listener started on port: " + replicationPort);
                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    listenerPool.submit(() -> handleReplicationClient(clientSocket, datastore));
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted() && serverSocket != null && !serverSocket.isClosed()) {
                    System.err.println("Replication listener error: " + e.getMessage());
                }
            }
        });
        listenerThread.start();
    }

    private void handleReplicationClient(Socket clientSocket, ConcurrentHashMap<String, CacheEntry> datastore) {
        try (
            Socket socket = clientSocket;
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            String line = in.readLine();
            if (line != null) {
                String[] parts = line.split("\\|");
                if (parts.length >= 4 && parts[0].equals("REPLICATE")) {
                    String action = parts[1];
                    String key = parts[2];

                    if (action.equals("PUT") && parts.length >= 5) {
                        String value = parts[3];
                        long timestamp = Long.parseLong(parts[4]);

                        datastore.compute(key, (k, existingEntry) -> {
                            if (existingEntry == null || timestamp > existingEntry.getTimestamp()) {
                                // Dùng TTL tối đa vì message đồng bộ không chứa TTL
                                return new CacheEntry(value, Long.MAX_VALUE, timestamp);
                            }
                            return existingEntry;
                        });
                        out.print("OK\n");
                        out.flush();

                    } else if (action.equals("DELETE")) {
                        long timestamp = Long.parseLong(parts[3]);

                        datastore.compute(key, (k, existingEntry) -> {
                            if (existingEntry != null && timestamp > existingEntry.getTimestamp()) {
                                return null;
                            }
                            return existingEntry;
                        });
                        out.print("OK\n");
                        out.flush();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling replication client: " + e.getMessage());
        }
    }
    
    /**
     * Tắt Thread pool khi server dừng hoạt động
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        if (listenerPool != null && !listenerPool.isShutdown()) {
            listenerPool.shutdown();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }
    }
}
