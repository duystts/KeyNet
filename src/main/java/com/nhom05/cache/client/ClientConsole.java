package com.nhom05.cache.client;

import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Demo console tuong tac cho CacheClient (Issue #7).
 *
 * Cach chay: java -cp ... com.nhom05.cache.client.ClientConsole [config.properties]
 * Mac dinh doc config/config.properties neu khong truyen tham so.
 *
 * Lenh ho tro:
 *   PUT <key> <value co dau cach>   hoac  PUT|key|value
 *   GET <key>                        hoac  GET|key
 *   DELETE <key>                     hoac  DELETE|key
 *   INFO <key>      - hien thi server chinh / du phong cua key
 *   SERVERS         - liet ke tat ca server trong cluster
 *   HELP            - hien thi huong dan
 *   EXIT / QUIT     - thoat
 */
public class ClientConsole {

    private static final String DEFAULT_CONFIG = "config/config.properties";

    private final CacheClient client;

    public ClientConsole(CacheClient client) {
        this.client = client;
    }

    /** Khoi dong console voi tham so dong lenh. */
    public static void main(String[] args) throws IOException {
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        CacheClient client = CacheClient.fromConfig(configPath);
        new ClientConsole(client).run();
    }

    /** Vong lap chinh doc lenh tu stdin va xu ly. */
    public void run() throws IOException {
        printWelcome();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        System.out.print("> ");
        while ((line = reader.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) {
                System.out.print("> ");
                continue;
            }
            boolean continueLoop = handleLine(line);
            if (!continueLoop) break;
            System.out.print("> ");
        }
        System.out.println("Tam biet!");
    }

    /**
     * Xu ly 1 dong lenh.
     *
     * @return false neu nguoi dung muon thoat, true neu tiep tuc
     */
    boolean handleLine(String line) {
        // Ho tro ca cu phap raw: PUT|key|value
        String normalized = normalizeRaw(line);

        String upper = normalized.toUpperCase();

        if (upper.equals("EXIT") || upper.equals("QUIT")) {
            return false;
        }
        if (upper.equals("HELP")) {
            printHelp();
            return true;
        }
        if (upper.equals("SERVERS")) {
            printServers();
            return true;
        }

        // Phan tich lenh theo cu phap than thien: LENH key [value...]
        String[] parts = normalized.split("\\s+", 3);
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "PUT" -> {
                if (parts.length < 3) {
                    System.out.println("Cu phap: PUT <key> <value>");
                } else {
                    String key = parts[1];
                    String value = parts[2];
                    String result = client.put(key, value);
                    System.out.println(result);
                }
            }
            case "GET" -> {
                if (parts.length < 2) {
                    System.out.println("Cu phap: GET <key>");
                } else {
                    String key = parts[1];
                    String result = client.get(key);
                    System.out.println(result);
                }
            }
            case "DELETE" -> {
                if (parts.length < 2) {
                    System.out.println("Cu phap: DELETE <key>");
                } else {
                    String key = parts[1];
                    String result = client.delete(key);
                    System.out.println(result);
                }
            }
            case "INFO" -> {
                if (parts.length < 2) {
                    System.out.println("Cu phap: INFO <key>");
                } else {
                    String key = parts[1];
                    printInfo(key);
                }
            }
            default -> System.out.println("Lenh khong ro: " + parts[0] + ". Go HELP de xem huong dan.");
        }
        return true;
    }

    /**
     * Neu dong co dang RAW (chua '|'), chuyen thanh cu phap co dau cach de xu ly thong nhat.
     * Vi du "PUT|k|hello world" -> "PUT k hello world"
     * Chi chuyen neu khong co dau cach truoc dau '|' dau tien.
     */
    private String normalizeRaw(String line) {
        // Neu khong chua '|' thi la cu phap binh thuong
        if (!line.contains("|")) return line;

        // Phan tich theo '|' (giu toi da 3 phan: CMD, key, value)
        String[] rawParts = line.split("\\|", 3);
        String cmd = rawParts[0].strip();

        return switch (cmd.toUpperCase()) {
            case "PUT" -> rawParts.length >= 3
                    ? "PUT " + rawParts[1] + " " + rawParts[2]
                    : line;
            case "GET" -> rawParts.length >= 2
                    ? "GET " + rawParts[1]
                    : line;
            case "DELETE" -> rawParts.length >= 2
                    ? "DELETE " + rawParts[1]
                    : line;
            default -> line;
        };
    }

    /** Hien thi thong tin server chinh / du phong cua key. */
    private void printInfo(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            System.out.println("Key khong hop le: " + key);
            return;
        }
        List<ServerAddress> servers = client.router().servers();
        int primary = client.router().primaryIndex(key);
        int replica = client.router().replicaIndex(primary);
        ServerAddress primaryAddr = servers.get(primary);
        ServerAddress replicaAddr = servers.get(replica);
        System.out.printf("Key \"%s\":%n", key);
        System.out.printf("  Server chinh  [%d]: %s:%d%n", primary, primaryAddr.host(), primaryAddr.port());
        System.out.printf("  Server du phong [%d]: %s:%d%n", replica, replicaAddr.host(), replicaAddr.port());
    }

    /** Liet ke tat ca server trong cluster. */
    private void printServers() {
        List<ServerAddress> servers = client.router().servers();
        System.out.println("Cluster co " + servers.size() + " server:");
        for (int i = 0; i < servers.size(); i++) {
            ServerAddress addr = servers.get(i);
            System.out.printf("  [%d] %s:%d%n", i, addr.host(), addr.port());
        }
    }

    private void printWelcome() {
        System.out.println("=== KeyNet Cache Console (Issue #7) ===");
        System.out.println("Go HELP de xem lenh, EXIT de thoat.");
        printServers();
    }

    private void printHelp() {
        System.out.println("""
                Lenh ho tro:
                  PUT <key> <value>   - Ghi key-value (value co the chua dau cach)
                  GET <key>           - Doc gia tri
                  DELETE <key>        - Xoa key
                  INFO <key>          - Hien thi server chinh/du phong cua key
                  SERVERS             - Liet ke tat ca server trong cluster
                  HELP                - Hien thi tro giup nay
                  EXIT / QUIT         - Thoat
                
                Cung chap nhan cu phap raw: PUT|key|value  GET|key  DELETE|key
                """);
    }
}
