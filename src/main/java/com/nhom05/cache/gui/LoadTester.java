package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Module 5 - Cong cu kiem thu tai cho he thong KeyNet (Issue #17).
 *
 * Tuan thu chat che dac ta:
 * - N luong x M request (PUT/GET).
 * - Do throughput (req/s), do tre trung binh / min / max (ms), ty le thanh cong (%).
 * - Thanh cong chi khi server tra dung giao thuc:
 *     + PUT -> OK
 *     + GET -> VALUE|... hoac NOTFOUND (do LRU loai bo hoac chua ghi).
 *     + ERROR|... hoac mat ket noi / null -> tinh la THAT BAI.
 * - Xuat bao cao docs/load_test_report.txt o che do GHI DE (khong noi them).
 * - Ho tro chay doc lap (CLI main), thong qua GUI Panel hoac nhung vao unit test.
 */
public class LoadTester {

    /**
     * Cau hinh tham so do tai.
     *
     * @param threads            so luong luong dong thoi (N >= 1)
     * @param requestsPerThread  so request moi luong thuc hien (M >= 1)
     * @param putPercentage      ty le request PUT (0 - 100), con lai la GET
     * @param keyCount           so luong key phan tan de test (>= 1)
     * @param reportPath         duong dan file bao cao (mac dinh docs/load_test_report.txt)
     */
    public record LoadTestConfig(
            int threads,
            int requestsPerThread,
            int putPercentage,
            int keyCount,
            String reportPath
    ) {
        public LoadTestConfig {
            if (threads < 1) {
                throw new IllegalArgumentException("So luong luong phai >= 1");
            }
            if (requestsPerThread < 1) {
                throw new IllegalArgumentException("So request moi luong phai >= 1");
            }
            if (putPercentage < 0 || putPercentage > 100) {
                throw new IllegalArgumentException("Ty le PUT phai trong khoang 0 - 100%");
            }
            if (keyCount < 1) {
                throw new IllegalArgumentException("So luong key phai >= 1");
            }
            if (reportPath == null || reportPath.trim().isEmpty()) {
                reportPath = "docs/load_test_report.txt";
            }
        }

        public static LoadTestConfig defaultConfig() {
            return new LoadTestConfig(10, 50, 50, 20, "docs/load_test_report.txt");
        }

        public int totalRequests() {
            return threads * requestsPerThread;
        }
    }

    /**
     * Ket qua thong ke do tai chi tiet.
     */
    public record LoadTestResult(
            int threads,
            int requestsPerThread,
            int totalRequests,
            long durationMs,
            double throughput,
            double avgLatencyMs,
            double minLatencyMs,
            double maxLatencyMs,
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            int successCount,
            int errorCount,
            double successRate,
            int putSuccessCount,
            int putErrorCount,
            int getSuccessCount,
            int getErrorCount,
            int getHitCount,
            int getMissCount,
            String reportPath,
            String reportContent
    ) {
    }

    /**
     * Interface callback bao tien do cho GUI hoac console.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int completedRequests, int totalRequests, double currentSuccessRate, String currentStatus);
    }

    /**
     * Kiem tra tinh hop le va xac dinh thanh cong theo dung dac ta giao thuc:
     * - PUT: thanh cong khi phan hoi bat dau bang "OK".
     * - GET: thanh cong khi phan hoi bat dau bang "VALUE|" hoac bang "NOTFOUND".
     * - ERROR|... hoac null -> that bai.
     */
    public static boolean isSuccess(String requestType, String responseLine) {
        if (responseLine == null || responseLine.startsWith("ERROR|")) {
            return false;
        }
        if ("PUT".equalsIgnoreCase(requestType)) {
            return "OK".equals(responseLine);
        } else if ("GET".equalsIgnoreCase(requestType)) {
            return responseLine.startsWith("VALUE|") || "NOTFOUND".equals(responseLine);
        }
        return false;
    }

    /**
     * Thuc thi kiem thu tai voi tham so cau hinh va callback tien do.
     */
    public static LoadTestResult runTest(CacheClient client, LoadTestConfig config) {
        return runTest(client, config, null, new AtomicBoolean(false));
    }

    /**
     * Thuc thi kiem thu tai ho tro huy (cancel) va callback tien do.
     */
    public static LoadTestResult runTest(
            CacheClient client,
            LoadTestConfig config,
            ProgressCallback callback,
            AtomicBoolean cancelFlag
    ) {
        int threads = config.threads();
        int requestsPerThread = config.requestsPerThread();
        int totalRequests = config.totalRequests();
        int putPercentage = config.putPercentage();
        int keyCount = config.keyCount();

        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        AtomicInteger putSuccessCount = new AtomicInteger(0);
        AtomicInteger putErrorCount = new AtomicInteger(0);
        AtomicInteger getSuccessCount = new AtomicInteger(0);
        AtomicInteger getErrorCount = new AtomicInteger(0);
        AtomicInteger getHitCount = new AtomicInteger(0);
        AtomicInteger getMissCount = new AtomicInteger(0);

        // Danh sach luu thoi gian do tre tung request (nanoseconds)
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(totalRequests));

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        long testStartTime = System.nanoTime();

        for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
            final int tId = threadIndex;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Cho tat ca luong san sang de ban dong thoi
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int reqIdx = 0; reqIdx < requestsPerThread; reqIdx++) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        break;
                    }

                    // Chon key ngau nhien trong pham vi keyCount de test ca Cache Hit va Miss (NOTFOUND)
                    int keyNum = ThreadLocalRandom.current().nextInt(keyCount);
                    String key = "load_key_" + keyNum;

                    // Xac dinh thao tac la PUT hay GET tron ngau nhien theo ty le putPercentage
                    boolean isPut;
                    if (putPercentage >= 100) {
                        isPut = true;
                    } else if (putPercentage <= 0) {
                        isPut = false;
                    } else {
                        isPut = ThreadLocalRandom.current().nextInt(100) < putPercentage;
                    }
                    String reqType = isPut ? "PUT" : "GET";

                    long startReq = System.nanoTime();
                    CacheClient.Response response = null;
                    try {
                        if (isPut) {
                            String value = "val_t" + tId + "_r" + reqIdx + "_" + System.currentTimeMillis();
                            response = client.send(key, ProtocolParser.put(key, value));
                        } else {
                            response = client.send(key, ProtocolParser.get(key));
                        }
                    } catch (Exception e) {
                        response = null;
                    }
                    long endReq = System.nanoTime();
                    latenciesNanos.add(endReq - startReq);

                    String line = (response != null) ? response.line() : null;
                    boolean success = isSuccess(reqType, line);

                    if (success) {
                        successCount.incrementAndGet();
                        if (isPut) {
                            putSuccessCount.incrementAndGet();
                        } else {
                            getSuccessCount.incrementAndGet();
                            if (line != null && line.startsWith("VALUE|")) {
                                getHitCount.incrementAndGet();
                            } else {
                                getMissCount.incrementAndGet();
                            }
                        }
                    } else {
                        errorCount.incrementAndGet();
                        if (isPut) {
                            putErrorCount.incrementAndGet();
                        } else {
                            getErrorCount.incrementAndGet();
                        }
                    }

                    int done = completedCount.incrementAndGet();
                    if (callback != null && (done % 5 == 0 || done == totalRequests)) {
                        double rate = (done > 0) ? (successCount.get() * 100.0 / done) : 0.0;
                        String statusMsg = "Da chay " + done + "/" + totalRequests
                                + " (Thanh cong: " + successCount.get() + ", Loi: " + errorCount.get() + ")";
                        callback.onProgress(done, totalRequests, rate, statusMsg);
                    }
                }
                doneLatch.countDown();
            });
        }

        // Kich hoat tat ca cac luong bat dau chay dong thoi
        testStartTime = System.nanoTime();
        startLatch.countDown();

        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long testEndTime = System.nanoTime();
        executor.shutdown();

        long durationMs = Math.max(1, (testEndTime - testStartTime) / 1_000_000);
        int finalCompleted = completedCount.get();
        int finalSuccess = successCount.get();
        int finalError = errorCount.get();
        double successRate = (finalCompleted > 0) ? (finalSuccess * 100.0 / finalCompleted) : 0.0;
        double throughput = (finalCompleted * 1000.0) / durationMs;

        // Tinh toan thong ke do tre (min, max, avg, percentiles)
        double avgLatencyMs = 0.0;
        double minLatencyMs = 0.0;
        double maxLatencyMs = 0.0;
        double p50LatencyMs = 0.0;
        double p95LatencyMs = 0.0;
        double p99LatencyMs = 0.0;

        List<Long> sortedLatencies;
        synchronized (latenciesNanos) {
            sortedLatencies = new ArrayList<>(latenciesNanos);
        }
        if (!sortedLatencies.isEmpty()) {
            Collections.sort(sortedLatencies);
            long sum = 0;
            for (long lat : sortedLatencies) {
                sum += lat;
            }
            avgLatencyMs = (sum / (double) sortedLatencies.size()) / 1_000_000.0;
            minLatencyMs = sortedLatencies.get(0) / 1_000_000.0;
            maxLatencyMs = sortedLatencies.get(sortedLatencies.size() - 1) / 1_000_000.0;

            p50LatencyMs = percentile(sortedLatencies, 50) / 1_000_000.0;
            p95LatencyMs = percentile(sortedLatencies, 95) / 1_000_000.0;
            p99LatencyMs = percentile(sortedLatencies, 99) / 1_000_000.0;
        }

        List<ServerAddress> servers = new ArrayList<>();
        if (client.router() != null) {
            for (int i = 0; i < client.router().serverCount(); i++) {
                servers.add(client.router().server(i));
            }
        }

        // Tao noi dung bao cao theo dung dac ta
        String reportContent = generateReportContent(
                config,
                durationMs,
                throughput,
                avgLatencyMs,
                minLatencyMs,
                maxLatencyMs,
                p50LatencyMs,
                p95LatencyMs,
                p99LatencyMs,
                finalCompleted,
                finalSuccess,
                finalError,
                successRate,
                putSuccessCount.get(),
                putErrorCount.get(),
                getSuccessCount.get(),
                getErrorCount.get(),
                getHitCount.get(),
                getMissCount.get(),
                servers
        );

        // Ghi de file bao cao docs/load_test_report.txt (khong noi them)
        writeReportToFile(reportContent, config.reportPath());

        return new LoadTestResult(
                threads,
                requestsPerThread,
                finalCompleted,
                durationMs,
                throughput,
                avgLatencyMs,
                minLatencyMs,
                maxLatencyMs,
                p50LatencyMs,
                p95LatencyMs,
                p99LatencyMs,
                finalSuccess,
                finalError,
                successRate,
                putSuccessCount.get(),
                putErrorCount.get(),
                getSuccessCount.get(),
                getErrorCount.get(),
                getHitCount.get(),
                getMissCount.get(),
                config.reportPath(),
                reportContent
        );
    }

    private static long percentile(List<Long> sortedList, double p) {
        if (sortedList.isEmpty()) return 0;
        int index = (int) Math.ceil((p / 100.0) * sortedList.size()) - 1;
        index = Math.max(0, Math.min(index, sortedList.size() - 1));
        return sortedList.get(index);
    }

    /**
     * Tao noi dung bao cao chi tiet, ro rang va chuan form UTF-8.
     */
    public static String generateReportContent(
            LoadTestConfig config,
            long durationMs,
            double throughput,
            double avgLatencyMs,
            double minLatencyMs,
            double maxLatencyMs,
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            int completedRequests,
            int successCount,
            int errorCount,
            double successRate,
            int putSuccess,
            int putError,
            int getSuccess,
            int getError,
            int getHit,
            int getMiss,
            List<ServerAddress> servers
    ) {
        StringBuilder sb = new StringBuilder();
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        sb.append("================================================================================\n");
        sb.append("                    KEYNET DISTRIBUTED CACHE - LOAD TEST REPORT                 \n");
        sb.append("================================================================================\n");
        sb.append("Thoi gian tao bao cao   : ").append(timeStr).append("\n");
        sb.append("Phien ban he thong      : KeyNet 1.0-SNAPSHOT (Nhom 05)\n");
        sb.append("Kiem thu vien           : Nguyen Van Nhat (MSSV: 2380601556)\n");
        sb.append("Issue trien khai        : #17 Load tester\n");
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("1. THONG SO KIEM THU (TEST CONFIGURATION)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.US, "- So luong luong dong thoi (N threads)   : %d\n", config.threads()));
        sb.append(String.format(Locale.US, "- So request tren moi luong (M requests)  : %d\n", config.requestsPerThread()));
        sb.append(String.format(Locale.US, "- Tong so request thuc te                : %d\n", completedRequests));
        sb.append(String.format(Locale.US, "- Ty le workload                         : %d%% PUT / %d%% GET\n",
                config.putPercentage(), 100 - config.putPercentage()));
        sb.append(String.format(Locale.US, "- Pham vi key phan tan (Key count)       : %d keys\n", config.keyCount()));
        sb.append("- Danh sach Cache Server muc tieu        :\n");
        if (servers != null && !servers.isEmpty()) {
            for (int i = 0; i < servers.size(); i++) {
                ServerAddress addr = servers.get(i);
                sb.append(String.format(Locale.US, "    * Server #%d: %s:%d\n", i, addr.host(), addr.port()));
            }
        } else {
            sb.append("    * (Khong co thong tin cum server)\n");
        }
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("2. KET QUA TONG QUAN (PERFORMANCE & RELIABILITY SUMMARY)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.US, "- Tong thoi gian chay (Duration)         : %d ms (%.2f s)\n",
                durationMs, durationMs / 1000.0));
        sb.append(String.format(Locale.US, "- Throughput dat duoc                    : %.2f req/s\n", throughput));
        sb.append(String.format(Locale.US, "- Tong so request da xu ly               : %d\n", completedRequests));
        sb.append(String.format(Locale.US, "- So request thanh cong                  : %d\n", successCount));
        sb.append(String.format(Locale.US, "- So request that bai                    : %d\n", errorCount));
        sb.append(String.format(Locale.US, "- Ty le thanh cong (Success Rate)        : %.2f %%\n", successRate));
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("3. THONG SO DO TRE CHI TIET (LATENCY STATISTICS - MS)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.US, "- Do tre trung binh (Average Latency)    : %.2f ms\n", avgLatencyMs));
        sb.append(String.format(Locale.US, "- Do tre nho nhat (Min Latency)          : %.2f ms\n", minLatencyMs));
        sb.append(String.format(Locale.US, "- Do tre lon nhat (Max Latency)          : %.2f ms\n", maxLatencyMs));
        sb.append(String.format(Locale.US, "- Trung vi (p50 Latency)                 : %.2f ms\n", p50LatencyMs));
        sb.append(String.format(Locale.US, "- Phan tram 95th (p95 Latency)           : %.2f ms\n", p95LatencyMs));
        sb.append(String.format(Locale.US, "- Phan tram 99th (p99 Latency)           : %.2f ms\n", p99LatencyMs));
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("4. PHAN TICH CHI TIET THEO LOAI TAC VU (OPERATION BREAKDOWN)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        int totalPut = putSuccess + putError;
        double putSuccessRate = (totalPut > 0) ? (putSuccess * 100.0 / totalPut) : 0.0;
        sb.append("+ Thao tac PUT:\n");
        sb.append(String.format(Locale.US, "  - Tong so request PUT                  : %d\n", totalPut));
        sb.append(String.format(Locale.US, "  - Thanh cong (phan hoi OK)              : %d (%.2f %%)\n", putSuccess, putSuccessRate));
        sb.append(String.format(Locale.US, "  - That bai                              : %d\n", putError));
        sb.append("\n");

        int totalGet = getSuccess + getError;
        double getSuccessRate = (totalGet > 0) ? (getSuccess * 100.0 / totalGet) : 0.0;
        sb.append("+ Thao tac GET:\n");
        sb.append(String.format(Locale.US, "  - Tong so request GET                  : %d\n", totalGet));
        sb.append(String.format(Locale.US, "  - Thanh cong (phan hoi VALUE / NOTFOUND): %d (%.2f %%)\n", getSuccess, getSuccessRate));
        sb.append(String.format(Locale.US, "    * Cache Hits (tra ve VALUE|...)       : %d\n", getHit));
        sb.append(String.format(Locale.US, "    * Cache Misses / LRU (tra ve NOTFOUND): %d\n", getMiss));
        sb.append(String.format(Locale.US, "  - That bai (ERROR|... hoac mat ket noi) : %d\n", getError));
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("5. KET LUAN KIEM DINH (VERIFICATION CRITERIA)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("[x] Tuan thu dung dac ta giao thuc: PUT -> OK, GET -> VALUE|... hoac NOTFOUND.\n");
        sb.append("[x] Loi phan hoi ERROR|... hoac mat ket noi socket duoc tinh la THAT BAI.\n");
        sb.append("[x] Bao cao duoc ghi de docs/load_test_report.txt (khong noi them) dung dac ta #17.\n");
        sb.append("================================================================================\n");

        return sb.toString();
    }

    /**
     * Ghi de noi dung bao cao vao file chi dinh (UTF-8, TRUNCATE_EXISTING).
     */
    public static void writeReportToFile(String reportContent, String filePath) {
        try {
            Path path = Path.of(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(
                    path,
                    reportContent,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            System.err.println("Khong the ghi bao cao ra " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Chay doc lap CLI cho Load Tester:
     *   java -cp target/keynet.jar com.nhom05.cache.gui.LoadTester [configPath] [threads] [requestsPerThread] [reportPath]
     */
    public static void main(String[] args) {
        String configPath = (args.length > 0) ? args[0] : "config/config.properties";
        int threads = (args.length > 1) ? Integer.parseInt(args[1]) : 10;
        int requestsPerThread = (args.length > 2) ? Integer.parseInt(args[2]) : 50;
        String reportPath = (args.length > 3) ? args[3] : "docs/load_test_report.txt";

        System.out.println("=================================================");
        System.out.println("    KEYNET LOAD TESTER (CLI - ISSUE #17)         ");
        System.out.println("=================================================");
        System.out.println("Config file       : " + configPath);
        System.out.println("So luong luong (N): " + threads);
        System.out.println("Request / luong(M): " + requestsPerThread);
        System.out.println("Tong so request   : " + (threads * requestsPerThread));
        System.out.println("File bao cao xuat : " + reportPath);
        System.out.println("Dang ket noi cum Cache Server...");

        CacheClient client;
        try {
            client = CacheClient.fromConfig(configPath);
        } catch (IOException e) {
            System.err.println("Khong doc duoc " + configPath + ", dung cau hinh mac dinh 3 server localhost: " + e.getMessage());
            ServerConfig fallback = ServerConfig.of(
                    List.of(
                            new ServerAddress("127.0.0.1", 5001),
                            new ServerAddress("127.0.0.1", 5002),
                            new ServerAddress("127.0.0.1", 5003)
                    ),
                    0
            );
            client = new CacheClient(fallback.servers());
        }

        LoadTestConfig config = new LoadTestConfig(threads, requestsPerThread, 50, 20, reportPath);

        System.out.println("Bat dau do tai...");
        long t0 = System.currentTimeMillis();

        LoadTestResult result = runTest(client, config, (done, total, rate, status) -> {
            if (done % (Math.max(1, total / 10)) == 0 || done == total) {
                System.out.printf(Locale.US, "Tien do: %d/%d (%.1f%%) - Ty le thanh cong: %.2f%%\n",
                        done, total, (done * 100.0 / total), rate);
            }
        }, new AtomicBoolean(false));

        long t1 = System.currentTimeMillis();
        System.out.println("\nHoan tat do tai trong " + (t1 - t0) + " ms!");
        System.out.printf(Locale.US, "Throughput: %.2f req/s\n", result.throughput());
        System.out.printf(Locale.US, "Do tre TB : %.2f ms (Min: %.2f ms, Max: %.2f ms)\n",
                result.avgLatencyMs(), result.minLatencyMs(), result.maxLatencyMs());
        System.out.printf(Locale.US, "Thanh cong : %d/%d (%.2f%%)\n",
                result.successCount(), result.totalRequests(), result.successRate());
        System.out.println("Bao cao da duoc ghi de vao: " + result.reportPath());
    }
}
