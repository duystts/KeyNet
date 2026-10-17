package com.nhom05.cache.client;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Demo console tuong tac cho CacheClient (Issue #7). Closes #7
 *
 * Cach chay:
 *   1. (Windows) chay "chcp 65001" truoc de console hieu UTF-8
 *   2. java -cp ... com.nhom05.cache.client.ClientConsole [config.properties]
 *      hoac: mvn exec:java -Dexec.mainClass=...ClientConsole -Dexec.args=config/config.properties
 *
 * Lenh ho tro:
 *   PUT <key> <value co dau cach>   hoac  PUT|key|value
 *   GET <key>                        hoac  GET|key
 *   DELETE <key>                     hoac  DELETE|key
 *   INFO <key>      - hien thi server chinh / du phong cua key
 *   SERVERS         - liet ke tat ca server trong cluster
 *   HELP            - hien thi huong dan
 *   EXIT / QUIT     - thoat
 *
 * Moi response duoc in kem "(server #N[, failover])" de de quan sat failover.
 */
public class ClientConsole {

    private static final String DEFAULT_CONFIG = "config/config.properties";

    private final CacheClient client;
    /** Output stream UTF-8 (thay the System.out de tranh loi charset tren Windows). */
    private final PrintStream out;

    public ClientConsole(CacheClient client) {
        this(client, new PrintStream(System.out, true, StandardCharsets.UTF_8));
    }

    /** Constructor cho test: inject PrintStream tuy y. */
    ClientConsole(CacheClient client, PrintStream out) {
        this.client = client;
        this.out = out;
    }

    /** Khoi dong console voi tham so dong lenh. */
    public static void main(String[] args) throws IOException {
        // Ghi chu: tren Windows nen chay "chcp 65001" truoc khi goi lenh nay
        // de console hieu ky tu UTF-8 (tieng Viet, ky tu dac biet).
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        CacheClient client = CacheClient.fromConfig(configPath);
        new ClientConsole(client).run();
    }

    /** Vong lap chinh doc lenh tu stdin va xu ly. */
    public void run() throws IOException {
        printWelcome();
        // Dung UTF-8 cho stdin tranh loi "Ph?m" tren Windows (Java 17 khong mac dinh UTF-8)
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        out.print("> ");
        while ((line = reader.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) {
                out.print("> ");
                continue;
            }
            boolean continueLoop = handleLine(line);
            if (!continueLoop) break;
            out.print("> ");
        }
        out.println("Tam biet!");
    }

    /**
     * Xu ly 1 dong lenh.
     *
     * @return false neu nguoi dung muon thoat, true neu tiep tuc
     */
    boolean handleLine(String line) {
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

        String[] parts = normalized.split("\\s+", 3);
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "PUT" -> {
                if (parts.length < 3) {
                    out.println("Cu phap: PUT <key> <value>");
                } else {
                    String key = parts[1];
                    String value = parts[2];
                    if (!ProtocolParser.isKeyValid(key)) {
                        out.println(ProtocolParser.error(ErrorCode.E002));
                    } else if (!ProtocolParser.isValueValid(value)) {
                        out.println(ProtocolParser.error(ErrorCode.E003));
                    } else {
                        CacheClient.Response resp = client.send(key, ProtocolParser.put(key, value));
                        out.println(formatResponse(resp));
                    }
                }
            }
            case "GET" -> {
                if (parts.length < 2) {
                    out.println("Cu phap: GET <key>");
                } else {
                    String key = parts[1];
                    if (!ProtocolParser.isKeyValid(key)) {
                        out.println(ProtocolParser.error(ErrorCode.E002));
                    } else {
                        CacheClient.Response resp = client.send(key, ProtocolParser.get(key));
                        out.println(formatResponse(resp));
                    }
                }
            }
            case "DELETE" -> {
                if (parts.length < 2) {
                    out.println("Cu phap: DELETE <key>");
                } else {
                    String key = parts[1];
                    if (!ProtocolParser.isKeyValid(key)) {
                        out.println(ProtocolParser.error(ErrorCode.E002));
                    } else {
                        CacheClient.Response resp = client.send(key, ProtocolParser.delete(key));
                        out.println(formatResponse(resp));
                    }
                }
            }
            case "INFO" -> {
                if (parts.length < 2) {
                    out.println("Cu phap: INFO <key>");
                } else {
                    printInfo(parts[1]);
                }
            }
            default -> out.println("Lenh khong ro: " + parts[0] + ". Go HELP de xem huong dan.");
        }
        return true;
    }

    /**
     * Format response kem thong tin server phuc vu va trang thai failover.
     * Vi du: "OK  (server #1, failover)"  hoac  "VALUE|hello  (server #0)"
     */
    private String formatResponse(CacheClient.Response resp) {
        String suffix = resp.failedOver()
                ? "  (server #" + resp.servedBy() + ", failover)"
                : "  (server #" + resp.servedBy() + ")";
        return resp.line() + suffix;
    }

    /**
     * Neu dong co dang RAW (chua '|'), chuyen thanh cu phap co dau cach.
     * Vi du "PUT|k|hello world" -> "PUT k hello world"
     */
    private String normalizeRaw(String line) {
        if (!line.contains("|")) return line;
        String[] rawParts = line.split("\\|", 3);
        String cmd = rawParts[0].strip();
        return switch (cmd.toUpperCase()) {
            case "PUT"    -> rawParts.length >= 3 ? "PUT "    + rawParts[1] + " " + rawParts[2] : line;
            case "GET"    -> rawParts.length >= 2 ? "GET "    + rawParts[1] : line;
            case "DELETE" -> rawParts.length >= 2 ? "DELETE " + rawParts[1] : line;
            default -> line;
        };
    }

    /** Hien thi thong tin server chinh / du phong cua key. */
    private void printInfo(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            out.println("Key khong hop le: " + key);
            return;
        }
        List<ServerAddress> servers = client.router().servers();
        int primary = client.router().primaryIndex(key);
        int replica = client.router().replicaIndex(primary);
        ServerAddress primaryAddr = servers.get(primary);
        ServerAddress replicaAddr = servers.get(replica);
        out.printf("Key \"%s\":%n", key);
        out.printf("  Server chinh    [%d]: %s:%d%n", primary, primaryAddr.host(), primaryAddr.port());
        out.printf("  Server du phong [%d]: %s:%d%n", replica, replicaAddr.host(), replicaAddr.port());
    }

    /** Liet ke tat ca server trong cluster. */
    private void printServers() {
        List<ServerAddress> servers = client.router().servers();
        out.println("Cluster co " + servers.size() + " server:");
        for (int i = 0; i < servers.size(); i++) {
            ServerAddress addr = servers.get(i);
            out.printf("  [%d] %s:%d%n", i, addr.host(), addr.port());
        }
    }

    private void printWelcome() {
        out.println("=== KeyNet Cache Console (Issue #7) ===");
        out.println("Tip (Windows): chay 'chcp 65001' truoc de hien thi tieng Viet dung.");
        out.println("Go HELP de xem lenh, EXIT de thoat.");
        printServers();
    }

    private void printHelp() {
        out.println("""
                Lenh ho tro:
                  PUT <key> <value>   - Ghi key-value (value co the chua dau cach)
                  GET <key>           - Doc gia tri
                  DELETE <key>        - Xoa key
                  INFO <key>          - Hien thi server chinh/du phong cua key
                  SERVERS             - Liet ke tat ca server trong cluster
                  HELP                - Hien thi tro giup nay
                  EXIT / QUIT         - Thoat
                
                Cung chap nhan cu phap raw: PUT|key|value  GET|key  DELETE|key
                Moi ket qua in kem "(server #N[, failover])" de quan sat failover.
                """);
    }
}
