package com.nhom05.cache.registry;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.server.CacheNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Issue #18 — Log minh chung Registry phat hien server chet va phuc hoi.
 *
 * Chay cum that (Registry + 3 Cache Server), tat 1 server, ghi lai qua trinh
 * Registry chuyen UP -> DOWN sau ~9 giay va DOWN -> UP khi bat lai.
 * Ket qua luu ra docs/log_minh_chung_server_chet.txt (UTF-8).
 *
 * Khong dung heartbeat gia — chay cum that voi CacheNode (co HeartbeatSender).
 *
 * Cach chay:
 *   mvn compile exec:java -Dexec.mainClass=com.nhom05.cache.registry.LogMinhChung
 * hoac:
 *   java -cp target/classes com.nhom05.cache.registry.LogMinhChung
 */
public final class LogMinhChung {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String OUTPUT_FILE = "docs/log_minh_chung_server_chet.txt";

    /** Server se bi tat de demo (server #1, port 5002). */
    private static final int TARGET_SERVER = 1;

    public static void main(String[] args) throws Exception {
        StringBuilder log = new StringBuilder();

        // Bat log cua com.nhom05.cache.registry de ghi truc tiep cac dong
        // [DEAD SERVER DETECTED] va [SERVER RECOVERED] vao file minh chung
        setupLogging(log);

        log(log, "=== LOG MINH CHUNG: REGISTRY PHAT HIEN SERVER CHET VA PHUC HOI ===");
        log(log, "Nhom 05 - Phu trach: Nguyen Tran Tuan Anh");
        log(log, "");

        // Doc cau hinh Registry tu config/config.properties
        ServerConfig baseConfig = ServerConfig.load("config/config.properties");
        int registryPort = baseConfig.registry().port();
        String registryHost = baseConfig.registry().host();

        // --- Buoc 1: Khoi dong Registry ---
        log(log, "--- BUOC 1: Khoi dong RegistryServer tai port " + registryPort + " (tu config) ---");
        RegistryServer registry = new RegistryServer(registryPort);
        registry.start();
        registry.awaitListening(3000);
        log(log, "RegistryServer da san sang tai port " + registry.localPort());

        // --- Buoc 2: Khoi dong 3 Cache Server (#0, #1, #2) ---
        log(log, "");
        log(log, "--- BUOC 2: Khoi dong 3 Cache Server (#0, #1, #2) ---");
        CacheNode[] nodes = new CacheNode[3];
        for (int i = 0; i < 3; i++) {
            ServerConfig cfg = baseConfig.withServerIndex(i);
            nodes[i] = new CacheNode(cfg);
            nodes[i].start();
            log(log, "CacheServer #" + i + " da khoi dong tai port " + cfg.self().port()
                    + " (replication port " + cfg.replicationPort(i) + ")");
        }

        // --- Buoc 2b: Ghi mot so du lieu mau qua CacheClient truoc khi tat ---
        log(log, "");
        log(log, "--- BUOC 2b: Ghi du lieu mau qua CacheClient (PUT cac key) ---");
        CacheClient cacheClient = new CacheClient(baseConfig.servers());
        String[][] sampleData = {
                {"key-0", "Value for node 0"},
                {"key-1", "Value for node 1"},
                {"key-2", "Value for node 2"},
                {"user:alice", "Alice Profile Data"},
                {"user:bob", "Bob Profile Data"},
                {"user:charlie", "Charlie Profile Data"}
        };
        for (String[] entry : sampleData) {
            String k = entry[0];
            String v = entry[1];
            String res = cacheClient.put(k, v);
            int primary = cacheClient.router().primaryIndex(k);
            log(log, "  PUT key=\"" + k + "\" (primary server #" + primary + ") -> " + res);
        }

        // Cho heartbeat gui so lieu thong ke ve Registry (it nhat 1 chu ky 3s)
        log(log, "Cho 4 giay de heartbeat cap nhat so lieu (keyCount, requestCount) ve Registry...");
        Thread.sleep(4000);

        // --- Buoc 3: Kiem tra trang thai truoc khi tat ---
        log(log, "");
        log(log, "--- BUOC 3: Kiem tra trang thai cac server TRUOC khi tat ---");
        RegistryClient client = new RegistryClient(registryHost, registryPort);
        logAllStatus(log, client);

        // --- Buoc 4: Tat server #1 ---
        log(log, "");
        log(log, "--- BUOC 4: Tat CacheServer #" + TARGET_SERVER + " (port "
                + baseConfig.servers().get(TARGET_SERVER).port() + ") ---");
        long lastHbBeforeStop = registry.lastHeartbeatOf(TARGET_SERVER);
        long timeSinceLastHb = System.currentTimeMillis() - lastHbBeforeStop;
        log(log, "Dong CacheNode #" + TARGET_SERVER + "...");
        nodes[TARGET_SERVER].close();
        log(log, "CacheServer #" + TARGET_SERVER + " da tat.");
        log(log, "  (Thoi diem heartbeat cuoi ghi nhan tai Registry: "
                + timeSinceLastHb + "ms truoc luc tat)");

        // --- Buoc 5: Cho Registry phat hien server chet (~9 giay) ---
        log(log, "");
        log(log, "--- BUOC 5: Cho Registry phat hien server #" + TARGET_SERVER + " chet (timeout 9 giay) ---");
        log(log, "  Giai thich: Timeout 9s tinh tu heartbeat cuoi (chu ky 3s), khong tinh tu luc tat node.");
        long startWait = System.currentTimeMillis();
        boolean detected = false;
        // Kiem tra moi 1 giay, toi da 20 giay
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            String status = client.status(TARGET_SERVER);
            long elapsed = System.currentTimeMillis() - startWait;
            long totalSinceHb = (lastHbBeforeStop > 0) ? (System.currentTimeMillis() - lastHbBeforeStop) : elapsed;
            log(log, "  [+" + elapsed + "ms tu luc tat | " + totalSinceHb + "ms tu heartbeat cuoi] STATUS server #"
                    + TARGET_SERVER + " = " + status);
            if ("DOWN".equals(status)) {
                log(log, ">>> Registry da phat hien server #" + TARGET_SERVER
                        + " CHET sau " + elapsed + "ms tu luc tat (~" + (elapsed / 1000)
                        + " giay, tuong ung " + totalSinceHb + "ms ~" + (totalSinceHb / 1000)
                        + " giay tu heartbeat cuoi - dung dac ta 9s) <<<");
                detected = true;
                break;
            }
        }
        if (!detected) {
            log(log, "!!! THAT BAI: Registry khong phat hien server chet trong 20 giay !!!");
        }

        // Cho them 3 giay de health checker (chu ky 1s) quet va in [DEAD SERVER DETECTED]
        log(log, "Doi 3 giay de health checker quet va ghi nhan [DEAD SERVER DETECTED]...");
        Thread.sleep(3000);

        // Log trang thai cac server sau khi 1 server chet
        log(log, "");
        log(log, "Trang thai cac server SAU khi server #" + TARGET_SERVER + " chet:");
        logAllStatus(log, client);

        // --- Buoc 6: Bat lai server #1 ---
        log(log, "");
        log(log, "--- BUOC 6: Bat lai CacheServer #" + TARGET_SERVER + " ---");
        ServerConfig cfg1 = baseConfig.withServerIndex(TARGET_SERVER);
        nodes[TARGET_SERVER] = new CacheNode(cfg1);
        nodes[TARGET_SERVER].start();
        log(log, "CacheServer #" + TARGET_SERVER + " da khoi dong lai.");

        // --- Buoc 7: Cho Registry phat hien server phuc hoi ---
        log(log, "");
        log(log, "--- BUOC 7: Cho Registry phat hien server #" + TARGET_SERVER + " phuc hoi ---");
        long startRecovery = System.currentTimeMillis();
        boolean recovered = false;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            String status = client.status(TARGET_SERVER);
            long elapsed = System.currentTimeMillis() - startRecovery;
            log(log, "  [+" + elapsed + "ms] STATUS server #" + TARGET_SERVER + " = " + status);
            if ("UP".equals(status)) {
                log(log, ">>> Registry da phat hien server #" + TARGET_SERVER
                        + " PHUC HOI sau " + elapsed + "ms (~ " + (elapsed / 1000) + " giay) <<<");
                recovered = true;
                break;
            }
        }
        if (!recovered) {
            log(log, "!!! THAT BAI: Registry khong phat hien server phuc hoi trong 20 giay !!!");
        }

        // Log trang thai cuoi cung
        log(log, "");
        log(log, "--- BUOC 8: Trang thai cuoi cung ---");
        logAllStatus(log, client);

        // Cho 1.5s de Registry cap nhat day du so lieu STATS tu heartbeat moi
        Thread.sleep(1500);

        // --- Buoc 9: STATS cua Registry ---
        log(log, "");
        log(log, "--- BUOC 9: So lieu thong ke tu Registry (STATS) ---");
        List<RegistryClient.NodeStat> stats = client.stats();
        for (RegistryClient.NodeStat ns : stats) {
            log(log, "  Server #" + ns.index() + ": " + (ns.up() ? "UP" : "DOWN")
                    + ", keyCount=" + ns.keyCount() + ", requestCount=" + ns.requestCount());
        }

        // --- Ket thuc ---
        log(log, "");
        log(log, "=== KET THUC LOG MINH CHUNG ===");

        // Luu file
        Path outputPath = Path.of(OUTPUT_FILE);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, log.toString(), StandardCharsets.UTF_8);
        System.out.println("\n>>> Da luu log minh chung vao: " + outputPath.toAbsolutePath());

        // Tat tat ca
        for (int i = 0; i < 3; i++) {
            if (nodes[i] != null) {
                nodes[i].close();
            }
        }
        registry.shutdown();
        System.out.println("Da tat toan bo cum.");

        // Tat han cac thread daemon con lai
        System.exit(0);
    }

    /** Ghi 1 dong log co timestamp vao StringBuilder va in ra console (thread-safe). */
    private static synchronized void log(StringBuilder sb, String message) {
        String timestamp = LocalDateTime.now().format(FMT);
        String line = "[" + timestamp + "] " + message;
        sb.append(line).append(System.lineSeparator());
        System.out.println(line);
    }

    /** Ghi trang thai UP/DOWN cua toan bo server qua RegistryClient. */
    private static void logAllStatus(StringBuilder sb, RegistryClient client) {
        for (int i = 0; i < 3; i++) {
            String status = client.status(i);
            log(sb, "  Server #" + i + ": " + (status != null ? status : "KHONG KET NOI DUOC REGISTRY"));
        }
    }

    /** Bat log cua com.nhom05.cache.registry ghi truc tiep vao chuoi log va console. */
    private static void setupLogging(StringBuilder log) {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.INFO);
        for (var h : rootLogger.getHandlers()) {
            h.setLevel(Level.INFO);
            if (h instanceof ConsoleHandler) {
                h.setFormatter(new SimpleFormatter());
            }
        }

        Logger regLogger = Logger.getLogger("com.nhom05.cache.registry");
        regLogger.setLevel(Level.INFO);
        regLogger.setUseParentHandlers(false);

        Formatter fmt = new Formatter() {
            @Override
            public String format(LogRecord record) {
                return formatMessage(record);
            }
        };

        Handler registryHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!isLoggable(record)) {
                    return;
                }
                String msg = fmt.format(record);
                log(log, msg);
            }

            @Override
            public void flush() {}

            @Override
            public void close() throws SecurityException {}
        };
        registryHandler.setLevel(Level.INFO);
        regLogger.addHandler(registryHandler);
    }
}
