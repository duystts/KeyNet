package com.nhom05.cache.client;

import com.nhom05.cache.common.ServerConfig.ServerAddress;
import com.nhom05.cache.registry.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Module 2 — Elegant Redis-Style Interactive CLI for KeyNet.
 * Phụ trách: Nguyễn Văn Nhật / Nhóm 05.
 *
 * Chạy CLI:
 *   java -cp target/keynet.jar com.nhom05.cache.client.CacheClientConsoleDemo
 */
public class CacheClientConsoleDemo {

    // Soft & Harmonious Color Palette (Tông màu dịu nhẹ, hài hòa, chống mỏi mắt)
    private static final String RESET       = "\u001B[0m";
    private static final String BOLD        = "\u001B[1m";
    
    private static final String SLATE       = "\u001B[38;5;244m"; // Xám dịu (Borders, Khung, Ký tự phân cách)
    private static final String SOFT_CYAN   = "\u001B[38;5;117m"; // Xanh da trời dịu (Tiêu đề, Nhãn chính)
    private static final String MINT_GREEN  = "\u001B[38;5;78m";  // Xanh lá bạc hà (Trạng thái ONLINE, Thành công)
    private static final String WARM_GOLD   = "\u001B[38;5;215m"; // Vàng ấm (Cảnh báo, NOTFOUND, Replica)
    private static final String CORAL_RED   = "\u001B[38;5;203m"; // Đỏ san hô (Lỗi, Trạng thái OFFLINE)
    private static final String PURPLE_SOFT = "\u001B[38;5;141m"; // Tím dịu (Hash code, Thông số phụ)
    private static final String PURE_WHITE  = "\u001B[38;5;255m"; // Trắng sáng (Nội dung Value, Lệnh)

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        String configPath = (args.length > 0) ? args[0] : "config/config.properties";

        printBanner();
        System.out.println(SOFT_CYAN + "⚙ Loading configuration: " + PURE_WHITE + configPath + RESET);

        CacheClient client;
        try {
            client = CacheClient.loadFromConfig(configPath);
        } catch (Exception e) {
            System.err.println(CORAL_RED + "❌ Failed to load config: " + e.getMessage() + RESET);
            return;
        }

        RegistryClient registryClient = new RegistryClient();
        List<ServerAddress> servers = client.getRouter().getServers();

        System.out.println(MINT_GREEN + "✔ Connected to Cluster with " + servers.size() + " Cache Nodes:" + RESET);
        printServerList(servers);
        printHelpCard();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                String prompt = buildPrompt(registryClient, servers.size());
                System.out.print(prompt);

                String line = reader.readLine();
                if (line == null) break;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.equalsIgnoreCase("EXIT") || trimmed.equalsIgnoreCase("QUIT")) {
                    System.out.println(WARM_GOLD + "👋 Goodbye! Exiting KeyNet CLI." + RESET);
                    break;
                }

                if (trimmed.equalsIgnoreCase("HELP")) {
                    printHelpCard();
                    continue;
                }

                if (trimmed.equalsIgnoreCase("CLEAR") || trimmed.equalsIgnoreCase("CLS")) {
                    clearScreen();
                    printBanner();
                    continue;
                }

                if (trimmed.equalsIgnoreCase("SERVERS")) {
                    printServerList(servers);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("STATS") || trimmed.equalsIgnoreCase("CLUSTER")) {
                    showClusterStats(registryClient);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("HEALTH")) {
                    checkHealthStatus(registryClient, servers);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("BENCHMARK")) {
                    runQuickBenchmark(client);
                    continue;
                }

                processCommand(client, trimmed);
            }
        } catch (Exception e) {
            System.err.println(CORAL_RED + "❌ Input error: " + e.getMessage() + RESET);
        }
    }

    private static String buildPrompt(RegistryClient registryClient, int totalNodes) {
        List<RegistryClient.ServerStatRecord> stats = registryClient.getParsedStats();
        if (stats.isEmpty()) {
            return BOLD + CORAL_RED + "keynet@cluster[OFFLINE]" + SLATE + "> " + RESET;
        }

        int upCount = 0;
        for (RegistryClient.ServerStatRecord s : stats) {
            if (s.status() == ServerNode.Status.UP) upCount++;
        }

        String statusColor = (upCount == totalNodes) ? MINT_GREEN : (upCount > 0 ? WARM_GOLD : CORAL_RED);
        return BOLD + statusColor + "keynet@cluster(" + upCount + "/" + totalNodes + "-UP)" + SLATE + "> " + RESET;
    }

    private static void processCommand(CacheClient client, String input) {
        if (input.toUpperCase().startsWith("INFO ")) {
            String key = input.substring(5).trim();
            showKeyInfo(client, key);
            return;
        }

        if (input.contains("|")) {
            String[] parts = input.split("\\|", -1);
            String key = parts.length > 1 ? parts[1] : "";
            System.out.println(SLATE + "➔ [RAW SEND] " + PURE_WHITE + input + RESET);
            String response = client.sendRaw(key, input);
            formatResponseOutput(response);
            return;
        }

        String[] parts = input.split("\\s+", 3);
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "PUT" -> {
                if (parts.length < 3) {
                    System.out.println(CORAL_RED + "❌ Invalid syntax! Usage: PUT <key> <value>" + RESET);
                    return;
                }
                String key = parts[1];
                String val = parts[2];
                showKeyInfo(client, key);
                System.out.println(SLATE + "➔ [EXEC] PUT|" + key + "|" + val + RESET);
                String resp = client.put(key, val);
                formatResponseOutput(resp);
            }
            case "GET" -> {
                if (parts.length < 2) {
                    System.out.println(CORAL_RED + "❌ Invalid syntax! Usage: GET <key>" + RESET);
                    return;
                }
                String key = parts[1];
                showKeyInfo(client, key);
                System.out.println(SLATE + "➔ [EXEC] GET|" + key + RESET);
                String resp = client.get(key);
                formatResponseOutput(resp);
            }
            case "DELETE", "DEL" -> {
                if (parts.length < 2) {
                    System.out.println(CORAL_RED + "❌ Invalid syntax! Usage: DELETE <key>" + RESET);
                    return;
                }
                String key = parts[1];
                showKeyInfo(client, key);
                System.out.println(SLATE + "➔ [EXEC] DELETE|" + key + RESET);
                String resp = client.delete(key);
                formatResponseOutput(resp);
            }
            default -> System.out.println(CORAL_RED + "❌ Unknown command '" + cmd + "'. Type HELP for reference." + RESET);
        }
    }

    private static void formatResponseOutput(String response) {
        if (response == null) {
            System.out.println(CORAL_RED + "⬅ [RESPONSE] NULL (No Connection)" + RESET);
        } else if ("OK".equalsIgnoreCase(response)) {
            System.out.println(MINT_GREEN + "⬅ [RESPONSE] " + BOLD + "OK" + RESET);
        } else if (response.startsWith("VALUE|")) {
            String val = response.substring(6);
            System.out.println(MINT_GREEN + "⬅ [RESPONSE] " + BOLD + "VALUE: " + PURE_WHITE + "\"" + val + "\"" + RESET);
        } else if ("NOTFOUND".equalsIgnoreCase(response)) {
            System.out.println(WARM_GOLD + "⬅ [RESPONSE] " + BOLD + "(nil) / NOTFOUND" + RESET);
        } else if (response.startsWith("ERROR|")) {
            System.out.println(CORAL_RED + "⬅ [RESPONSE] " + BOLD + response + RESET);
        } else {
            System.out.println(PURE_WHITE + "⬅ [RESPONSE] " + response + RESET);
        }
    }

    private static void showKeyInfo(CacheClient client, String key) {
        HashRouter router = client.getRouter();
        int primaryIdx = router.getPrimaryServerIndex(key);
        ServerAddress primaryAddr = router.getPrimaryServer(key);
        int replicaIdx = router.getReplicaServerIndex(primaryIdx);
        ServerAddress replicaAddr = router.getReplicaServer(primaryIdx);

        System.out.println(SLATE + "┌─ [ROUTER INFO] ────────────────────────────────────────────────────────┐" + RESET);
        System.out.printf(SLATE + "│ " + SOFT_CYAN + "Key: " + PURE_WHITE + "%-15s" + SLATE + " │ Hash: " + PURPLE_SOFT + "%-10d" + SLATE + " │ Mod N: " + MINT_GREEN + "%-2d" + SLATE + "                 │\n" + RESET,
                key, key.hashCode(), primaryIdx);
        System.out.printf(SLATE + "│ " + MINT_GREEN + "► Primary Node  : Server #%-2d (%s:%d)" + SLATE + "                              │\n" + RESET,
                primaryIdx, primaryAddr.host(), primaryAddr.port());
        System.out.printf(SLATE + "│ " + WARM_GOLD + "► Replica Node  : Server #%-2d (%s:%d)" + SLATE + "                              │\n" + RESET,
                replicaIdx, replicaAddr.host(), replicaAddr.port());
        System.out.println(SLATE + "└────────────────────────────────────────────────────────────────────────┘" + RESET);
    }

    private static void showClusterStats(RegistryClient registryClient) {
        List<RegistryClient.ServerStatRecord> stats = registryClient.getParsedStats();
        System.out.println(BOLD + SOFT_CYAN + "\n=================== CLUSTER DASHBOARD & STATS ===================" + RESET);
        System.out.println(SLATE + "┌──────────────┬──────────────────┬──────────────┬─────────────┬────────────────┐" + RESET);
        System.out.println(SLATE + "│ " + SOFT_CYAN + "Server Index " + SLATE + "│ " + SOFT_CYAN + "Address          " + SLATE + "│ " + SOFT_CYAN + "Status       " + SLATE + "│ " + SOFT_CYAN + "Key Count   " + SLATE + "│ " + SOFT_CYAN + "Total Requests " + SLATE + "│" + RESET);
        System.out.println(SLATE + "├──────────────┼──────────────────┼──────────────┼─────────────┼────────────────┤" + RESET);

        if (stats.isEmpty()) {
            System.out.println(CORAL_RED + "│ (No Stats returned from Registry - Is RegistryServer active on 6000?)        │" + RESET);
        } else {
            for (RegistryClient.ServerStatRecord s : stats) {
                String statusStr = (s.status() == ServerNode.Status.UP) ? MINT_GREEN + "UP    " + RESET : CORAL_RED + "DOWN  " + RESET;
                System.out.printf(SLATE + "│ " + PURE_WHITE + "Server #%-6d" + SLATE + " │ " + PURE_WHITE + "127.0.0.1:500%-2d" + SLATE + " │ %s       │ " + PURE_WHITE + "%-11d" + SLATE + " │ " + PURE_WHITE + "%-14d" + SLATE + " │\n" + RESET,
                        s.serverIndex(), (s.serverIndex() + 1), statusStr, s.keyCount(), s.requestCount());
            }
        }
        System.out.println(SLATE + "└──────────────┴──────────────────┴──────────────┴─────────────┴────────────────┘" + RESET);
    }

    private static void checkHealthStatus(RegistryClient registryClient, List<ServerAddress> servers) {
        System.out.println(BOLD + SOFT_CYAN + "\n🔍 Running Health Diagnostics..." + RESET);
        boolean regUp = registryClient.getRawStats() != null;
        System.out.println(SLATE + " Registry Server (Port 6000): " + (regUp ? MINT_GREEN + "[HEALTHY UP]" : CORAL_RED + "[UNREACHABLE DOWN]") + RESET);

        for (int i = 0; i < servers.size(); i++) {
            boolean isUp = registryClient.isServerUp(i);
            ServerAddress addr = servers.get(i);
            System.out.printf(SLATE + " Server #%d (%s:%d): %s\n",
                    i, addr.host(), addr.port(), (isUp ? MINT_GREEN + "[ONLINE UP]" : CORAL_RED + "[OFFLINE DOWN]") + RESET);
        }
    }

    private static void runQuickBenchmark(CacheClient client) {
        System.out.println(WARM_GOLD + "⚡ Running Quick Benchmark (100 Requests)..." + RESET);
        long start = System.currentTimeMillis();
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            String key = "bench_key_" + i;
            String res = client.put(key, "val_" + i);
            if ("OK".equalsIgnoreCase(res)) ok++;
        }
        long duration = System.currentTimeMillis() - start;
        System.out.println(MINT_GREEN + "✔ Benchmark Finished!" + RESET);
        System.out.printf(PURE_WHITE + "  Total Requests: 100 | Success: %d | Time: %d ms | Throughput: %.2f ops/sec\n" + RESET,
                ok, duration, (100.0 / (duration / 1000.0 + 0.001)));
    }

    private static void printServerList(List<ServerAddress> servers) {
        for (int i = 0; i < servers.size(); i++) {
            System.out.printf("  " + MINT_GREEN + "●" + PURE_WHITE + " Server #%d -> " + SOFT_CYAN + "%s:%d\n" + RESET,
                    i, servers.get(i).host(), servers.get(i).port());
        }
    }

    private static void printBanner() {
        System.out.println(SLATE + "────────────────────────────────────────────────────────────────────\n" + RESET
                + BOLD + SOFT_CYAN + "  KEYNET — DISTRIBUTED CACHE SYSTEM (INTERACTIVE CLI)   \n" + RESET
                + SLATE + "────────────────────────────────────────────────────────────────────" + RESET);
    }

    private static void printHelpCard() {
        System.out.println(BOLD + SOFT_CYAN + "\n📋 SUPPORTED COMMANDS REFERENCE:" + RESET);
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "PUT <key> <val>    " + SLATE + "- Write key-value to cache");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "GET <key>          " + SLATE + "- Retrieve value of key from cache");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "DELETE <key>       " + SLATE + "- Delete key from cache");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "INFO <key>         " + SLATE + "- View Hash Routing (Primary & Replica Nodes)");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "STATS / CLUSTER    " + SLATE + "- View real-time cluster status & metrics");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "HEALTH             " + SLATE + "- Run diagnostic health check on all nodes");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "BENCHMARK          " + SLATE + "- Run quick 100-request latency benchmark");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "SERVERS            " + SLATE + "- List all configured server addresses");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "CLEAR / CLS        " + SLATE + "- Clear console screen");
        System.out.println(SLATE + "  " + BOLD + PURE_WHITE + "EXIT / QUIT        " + SLATE + "- Quit KeyNet CLI\n" + RESET);
    }

    private static void clearScreen() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {
        }
    }
}
