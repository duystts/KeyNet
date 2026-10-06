package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Module 5 — Multi-threaded Load Testing Tool.
 * Phụ trách: Nguyễn Văn Nhật.
 *
 * Kiểm thử hiệu năng và khả năng chịu tải của cụm KeyNet Distributed Cache System:
 * - Giả lập N luồng truy cập đồng thời.
 * - Thực hiện M requests (PUT và GET).
 * - Đo Throughput (ops/sec), Latency trung bình (ms), Tỷ lệ thành công.
 * - Xuất báo cáo benchmark ra file docs/load_test_report.txt.
 */
public class LoadTester {

    private static final String DEFAULT_CONFIG = "config/config.properties";

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int totalRequests = args.length > 1 ? Integer.parseInt(args[1]) : 2000;

        System.out.println("=================================================");
        System.out.println("   KEYNET DISTRIBUTED CACHE - LOAD TESTER TOOL   ");
        System.out.println("=================================================");
        System.out.println("Cấu hình kiểm thử:");
        System.out.println(" - Số luồng đồng thời (Threads): " + threads);
        System.out.println(" - Tổng số Requests (Requests): " + totalRequests);
        System.out.println(" - Tỷ lệ ghi/đọc (Write/Read): 30% PUT / 70% GET");
        System.out.println("-------------------------------------------------");

        try {
            CacheClient client = CacheClient.loadFromConfig(DEFAULT_CONFIG);
            runBenchmark(client, threads, totalRequests);
        } catch (Exception e) {
            System.err.println("Lỗi khi chạy Load Test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void runBenchmark(CacheClient client, int threadCount, int totalRequests) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong totalLatencyMs = new AtomicLong(0);
        AtomicLong minLatencyMs = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxLatencyMs = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i;
            pool.submit(() -> {
                long start = System.currentTimeMillis();
                boolean isPut = (requestId % 10) < 3; // 30% PUT, 70% GET
                String key = "loadtest_key_" + (requestId % 100);
                String val = "loadtest_val_" + requestId;

                try {
                    if (isPut) {
                        String res = client.put(key, val);
                        if ("OK".equalsIgnoreCase(res)) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } else {
                        client.get(key);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    long latency = System.currentTimeMillis() - start;
                    totalLatencyMs.addAndGet(latency);

                    minLatencyMs.accumulateAndGet(latency, Math::min);
                    maxLatencyMs.accumulateAndGet(latency, Math::max);
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);

        long totalTimeMs = System.currentTimeMillis() - startTime;
        double totalSec = totalTimeMs / 1000.0;
        long succ = successCount.get();
        long err = errorCount.get();
        double throughput = totalSec > 0 ? (succ + err) / totalSec : 0;
        double avgLatency = (succ + err) > 0 ? (double) totalLatencyMs.get() / (succ + err) : 0;

        StringBuilder report = new StringBuilder();
        report.append("\n=================================================\n");
        report.append("          BÁO CÁO KẾT QUẢ LOAD TEST              \n");
        report.append("=================================================\n");
        report.append("Thời gian thực hiện    : ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        report.append("Số luồng đồng thời     : ").append(threadCount).append(" threads\n");
        report.append("Tổng số Requests       : ").append(totalRequests).append("\n");
        report.append("Số request thành công  : ").append(succ).append(" (").append(String.format("%.2f", (succ * 100.0 / totalRequests))).append("%)\n");
        report.append("Số request thất bại    : ").append(err).append(" (").append(String.format("%.2f", (err * 100.0 / totalRequests))).append("%)\n");
        report.append("Tổng thời gian thực thi: ").append(String.format("%.3f", totalSec)).append(" giây\n");
        report.append("Throughput (Chịu tải)  : ").append(String.format("%.2f", throughput)).append(" ops/sec\n");
        report.append("Độ trễ trung bình      : ").append(String.format("%.2f", avgLatency)).append(" ms\n");
        report.append("Độ trễ nhỏ nhất (Min)  : ").append(minLatencyMs.get() == Long.MAX_VALUE ? 0 : minLatencyMs.get()).append(" ms\n");
        report.append("Độ trễ lớn nhất (Max)  : ").append(maxLatencyMs.get()).append(" ms\n");
        report.append("=================================================\n");

        System.out.println(report);

        // Lưu báo cáo ra file docs/load_test_report.txt
        try (PrintWriter writer = new PrintWriter(new FileWriter("docs/load_test_report.txt", StandardCharsets.UTF_8, true))) {
            writer.println(report);
            System.out.println("✅ Đã xuất file báo cáo load test tại: docs/load_test_report.txt");
        } catch (IOException e) {
            System.err.println("Không thể lưu file báo cáo: " + e.getMessage());
        }
    }
}
