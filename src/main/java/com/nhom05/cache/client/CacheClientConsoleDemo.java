package com.nhom05.cache.client;

import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Module 2 - Demo ứng dụng Client giao diện Console.
 *
 * Chạy demo qua console:
 *   java -cp target/keynet.jar com.nhom05.cache.client.CacheClientConsoleDemo [path/to/config.properties]
 */
public class CacheClientConsoleDemo {

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        String configPath = (args.length > 0) ? args[0] : "config/config.properties";
        System.out.println("=================================================");
        System.out.println("     KEYNET DISTRIBUTED CACHE CLIENT - MODULE 2  ");
        System.out.println("=================================================");
        System.out.println("Dang tai cau hinh tu file: " + configPath);

        CacheClient client;
        try {
            client = CacheClient.loadFromConfig(configPath);
        } catch (Exception e) {
            System.err.println("Loi khi tai cau hinh: " + e.getMessage());
            System.err.println("Vui long kiem tra lai file config va duong dan!");
            return;
        }

        List<ServerAddress> servers = client.getRouter().getServers();
        System.out.println("Da ket noi toi cluster gom " + servers.size() + " server:");
        for (int i = 0; i < servers.size(); i++) {
            System.out.println("  Server #" + i + " -> " + servers.get(i).host() + ":" + servers.get(i).port());
        }

        printHelp();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("\nKeyNet-Client> ");
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.equalsIgnoreCase("EXIT") || trimmed.equalsIgnoreCase("QUIT")) {
                    System.out.println("Da thoat KeyNet Client Demo.");
                    break;
                }

                if (trimmed.equalsIgnoreCase("HELP")) {
                    printHelp();
                    continue;
                }

                if (trimmed.equalsIgnoreCase("SERVERS")) {
                    System.out.println("Danh sach Cluster Server:");
                    for (int i = 0; i < servers.size(); i++) {
                        System.out.println("  Server #" + i + " -> " + servers.get(i).host() + ":" + servers.get(i).port());
                    }
                    continue;
                }

                processCommand(client, trimmed);
            }
        } catch (Exception e) {
            System.err.println("Loi doc input: " + e.getMessage());
        }
    }

    private static void processCommand(CacheClient client, String input) {
        // Ho tro ca format pipe "PUT|key|val" va format cach ra "PUT key val" hoac "INFO key"
        if (input.toUpperCase().startsWith("INFO ")) {
            String key = input.substring(5).trim();
            showKeyInfo(client, key);
            return;
        }

        if (input.contains("|")) {
            // Raw protocol format (PUT|key|val, GET|key, DELETE|key)
            String[] parts = input.split("\\|", -1);
            String key = parts.length > 1 ? parts[1] : "";
            System.out.println("[Request] -> " + input);
            String response = client.sendRaw(key, input);
            System.out.println("[Response] <- " + response);
            return;
        }

        String[] parts = input.split("\\s+", 3);
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "PUT" -> {
                if (parts.length < 3) {
                    System.out.println("Cu phap sai! Dung: PUT <key> <value> hoac PUT|<key>|<value>");
                    return;
                }
                String key = parts[1];
                String val = parts[2];
                showKeyInfo(client, key);
                System.out.println("[Request] -> PUT|" + key + "|" + val);
                String resp = client.put(key, val);
                System.out.println("[Response] <- " + resp);
            }
            case "GET" -> {
                if (parts.length < 2) {
                    System.out.println("Cu phap sai! Dung: GET <key> hoac GET|<key>");
                    return;
                }
                String key = parts[1];
                showKeyInfo(client, key);
                System.out.println("[Request] -> GET|" + key);
                String resp = client.get(key);
                System.out.println("[Response] <- " + resp);
            }
            case "DELETE", "DEL" -> {
                if (parts.length < 2) {
                    System.out.println("Cu phap sai! Dung: DELETE <key> hoac DELETE|<key>");
                    return;
                }
                String key = parts[1];
                showKeyInfo(client, key);
                System.out.println("[Request] -> DELETE|" + key);
                String resp = client.delete(key);
                System.out.println("[Response] <- " + resp);
            }
            default -> System.out.println("Lenh khong hop le: '" + cmd + "'. Go HELP de xem huong dan.");
        }
    }

    private static void showKeyInfo(CacheClient client, String key) {
        HashRouter router = client.getRouter();
        int primaryIdx = router.getPrimaryServerIndex(key);
        ServerAddress primaryAddr = router.getPrimaryServer(key);
        int replicaIdx = router.getReplicaServerIndex(primaryIdx);
        ServerAddress replicaAddr = router.getReplicaServer(primaryIdx);

        System.out.printf("  [Router Info] Key '%s' (hash: %d) -> Server Chinh: #%d (%s:%d) | Server Du Phong: #%d (%s:%d)\n",
                key, key.hashCode(), primaryIdx, primaryAddr.host(), primaryAddr.port(), replicaIdx, replicaAddr.host(), replicaAddr.port());
    }

    private static void printHelp() {
        System.out.println("""
                
                Cac lenh duoc ho tro:
                  PUT <key> <value>       - Ghi hoac cap nhat value cho key
                  GET <key>               - Lay value cua key tu cache
                  DELETE <key>            - Xoa key khoi cache
                  INFO <key>              - Xem thong tin dinh tuyen (Primary Server, Replica Server) cua key
                  SERVERS                 - Xem danh sach cac Cache Server trong cluster
                  HELP                    - Hien thi huong dan nay
                  EXIT / QUIT             - Thoat chuong trinh
                
                Ban cung co the go truc tiep cu phap giao thuc: PUT|key|val, GET|key, DELETE|key
                """);
    }
}
