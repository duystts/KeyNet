package com.nhom05.cache.gui;

import com.nhom05.cache.registry.RegistryServer;
import com.nhom05.cache.server.CacheServer;

import java.net.Socket;

/**
 * Lớp hỗ trợ 1-Click khởi động toàn bộ cụm KeyNet Cluster:
 * - RegistryServer (Port 6000 - nếu chưa mở)
 * - CacheServer #0 (Port 5001)
 * - CacheServer #1 (Port 5002)
 * - CacheServer #2 (Port 5003)
 *
 * Chạy trực tiếp trong NetBeans bằng nút Run File (Shift + F6).
 */
public class RunAllServers {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   🚀 KEYNET CLUSTER AUTOMATIC 1-CLICK LAUNCHER (NETBEANS)");
        System.out.println("=================================================================");

        // 1. Kiểm tra và khởi động Registry Server trên Port 6000 nếu chưa chạy
        if (!isPortOpen("127.0.0.1", 6000)) {
            System.out.println("🌐 Đang khởi chạy Registry Server trên Port 6000...");
            new Thread(() -> {
                try {
                    RegistryServer.main(new String[]{"6000"});
                } catch (Exception e) {
                    System.err.println("❌ Cảnh báo RegistryServer: " + e.getMessage());
                }
            }, "RegistryServer-Thread").start();

            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        } else {
            System.out.println("✅ Registry Server đã hoạt động sẵn trên Port 6000.");
        }

        // 2. Khởi chạy Cache Server #0 (Port 5001)
        startServerAsync(0, 5001, 0);

        // 3. Khởi chạy Cache Server #1 (Port 5002)
        startServerAsync(1, 5002, 500);

        // 4. Khởi chạy Cache Server #2 (Port 5003)
        startServerAsync(2, 5003, 1000);

        System.out.println("=================================================================");
        System.out.println("🎉 Cụm KeyNet Cluster đã khởi động hoàn tất! Bạn có thể bật GUI.");
        System.out.println("=================================================================");
    }

    private static void startServerAsync(int serverIdx, int port, long delayMs) {
        new Thread(() -> {
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                if (isPortOpen("127.0.0.1", port)) {
                    System.out.println("⚠️ Cache Server #" + serverIdx + " (Port " + port + ") đã đang chạy sẵn.");
                    return;
                }
                System.out.println("⚡ Đang khởi chạy Cache Server #" + serverIdx + " (Port " + port + ")...");
                CacheServer.main(new String[]{"config/config.properties", String.valueOf(serverIdx)});
            } catch (Throwable t) {
                System.err.println("❌ Không thể chạy Cache Server #" + serverIdx + ": " + t.getMessage());
            }
        }, "CacheServer-" + serverIdx).start();
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
