package com.nhom05.cache.replication;

import com.nhom05.cache.server.CacheEntry;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class ReplicationTest {

    public static void main(String[] args) throws InterruptedException {
        // 1. Khởi tạo datastore cho server dự phòng
        ConcurrentHashMap<String, CacheEntry> datastore = new ConcurrentHashMap<>();
        
        // 2. Mở startReplicationListener ở port 5011
        ReplicationHandler backupServer = new ReplicationHandler();
        backupServer.startReplicationListener(5011, datastore);
        
        // Chờ 1 chút để listener khởi động hoàn tất
        Thread.sleep(500);
        
        System.out.println("=== BẮT ĐẦU TEST XUNG ĐỘT (CONFLICT RESOLUTION) ===\n");

        // Lệnh 1: Ghi bình thường
        System.out.println("[+] Gửi Lệnh 1: Timestamp 1000");
        sendRawMessage("127.0.0.1", 5011, "REPLICATE|PUT|user:1|Nguyen Tien Duc|1000\n");
        Thread.sleep(500); // Chờ xử lý mạng
        printDatastoreState(datastore);

        // Lệnh 3: Đến sớm do mạng của lệnh 2 bị lỗi / lag
        System.out.println("\n[+] Gửi Lệnh 3 (đến trước lệnh 2): Timestamp 2000");
        sendRawMessage("127.0.0.1", 5011, "REPLICATE|PUT|user:1|Nguyen Tien Duc - Old Update|2000\n");
        Thread.sleep(500);
        printDatastoreState(datastore);
        
        // Lệnh 2: Update mới nhất nhưng bị trễ trên đường truyền
        System.out.println("\n[+] Gửi Lệnh 2 (đến trễ): Timestamp 3000");
        sendRawMessage("127.0.0.1", 5011, "REPLICATE|PUT|user:1|Nguyen Tien Duc - Updated|3000\n");
        Thread.sleep(500);
        printDatastoreState(datastore);

        // Gửi thử một lệnh có timestamp cũ rích để chứng minh xung đột sẽ bị loại bỏ
        System.out.println("\n[+] Gửi lại một lệnh rác (Timestamp 500) để cố gắng phá hoại:");
        sendRawMessage("127.0.0.1", 5011, "REPLICATE|PUT|user:1|Hacker|500\n");
        Thread.sleep(500);
        printDatastoreState(datastore);

        // Xác nhận kết quả
        System.out.println("\n=== KẾT QUẢ CUỐI CÙNG ===");
        CacheEntry finalEntry = datastore.get("user:1");
        if (finalEntry != null) {
            String val = finalEntry.value();
            System.out.println("=> Giá trị lưu trữ cuối cùng của user:1 là: '" + val + "'");
            if ("Nguyen Tien Duc - Updated".equals(val)) {
                System.out.println("=> TEST PASSED: Conflict resolution hoạt động chính xác!");
            } else {
                System.out.println("=> TEST FAILED: Sai giá trị mong đợi.");
            }
        } else {
            System.out.println("=> TEST FAILED: Không tìm thấy bản ghi.");
        }

        // Dọn dẹp tài nguyên
        backupServer.shutdown();
        // Thoát hẳn jvm vì executor service bên trong ReplicationHandler có thể giữ chương trình chạy
        System.exit(0);
    }

    /**
     * Hàm giả lập Client/Server chính gửi lệnh qua Socket
     */
    private static void sendRawMessage(String ip, int port, String message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 2000);
            socket.setSoTimeout(2000);
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                out.print(message);
                out.flush();
            }
        } catch (Exception e) {
            System.err.println("Lỗi khi gửi lệnh giả lập: " + e.getMessage());
        }
    }

    /**
     * Hàm in trạng thái datastore
     */
    private static void printDatastoreState(ConcurrentHashMap<String, CacheEntry> datastore) {
        CacheEntry entry = datastore.get("user:1");
        if (entry != null) {
            System.out.println("    -> Datastore [user:1]: '" + entry.value() + "' (Timestamp: " + entry.getTimestamp() + ")");
        } else {
            System.out.println("    -> Datastore [user:1]: NULL");
        }
    }
}
