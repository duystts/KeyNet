package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig.ServerAddress;
import com.nhom05.cache.gui.LoadTester.LoadTestConfig;
import com.nhom05.cache.gui.LoadTester.LoadTestResult;
import com.nhom05.cache.server.CacheServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test cho Cong cu Kiem thu tai LoadTester (Issue #17).
 */
class LoadTesterTest {

    @Test
    void testProtocolSuccessClassification() {
        // PUT thanh cong chi khi tra ve OK hoac OK|...
        assertTrue(LoadTester.isSuccess("PUT", "OK"));
        assertTrue(LoadTester.isSuccess("PUT", "OK|123456789"));
        assertFalse(LoadTester.isSuccess("PUT", "ERROR|E001|Sai cu phap"));
        assertFalse(LoadTester.isSuccess("PUT", "ERROR|E004|Khong ket noi duoc"));
        assertFalse(LoadTester.isSuccess("PUT", null));
        assertFalse(LoadTester.isSuccess("PUT", ""));

        // GET thanh cong khi tra ve VALUE|... HOAC NOTFOUND (theo dung dac ta #17)
        assertTrue(LoadTester.isSuccess("GET", "VALUE|user123"));
        assertTrue(LoadTester.isSuccess("GET", "NOTFOUND"));
        assertFalse(LoadTester.isSuccess("GET", "ERROR|E001|Sai cu phap"));
        assertFalse(LoadTester.isSuccess("GET", "ERROR|E004|Server chet"));
        assertFalse(LoadTester.isSuccess("GET", null));
        assertFalse(LoadTester.isSuccess("GET", "UNKNOWN"));
    }

    @Test
    void testLoadTestConfigValidation() {
        LoadTestConfig valid = new LoadTestConfig(8, 25, 60, 15, "docs/test_report.txt");
        assertEquals(8, valid.threads());
        assertEquals(25, valid.requestsPerThread());
        assertEquals(200, valid.totalRequests());
        assertEquals(60, valid.putPercentage());
        assertEquals(15, valid.keyCount());
        assertEquals("docs/test_report.txt", valid.reportPath());

        // Mac dinh khi null / blank reportPath
        LoadTestConfig fallback = new LoadTestConfig(5, 10, 50, 10, "   ");
        assertEquals("docs/load_test_report.txt", fallback.reportPath());

        // Loi khi tham so khong hop le
        assertThrows(IllegalArgumentException.class, () -> new LoadTestConfig(0, 10, 50, 10, "path"));
        assertThrows(IllegalArgumentException.class, () -> new LoadTestConfig(5, 0, 50, 10, "path"));
        assertThrows(IllegalArgumentException.class, () -> new LoadTestConfig(5, 10, -1, 10, "path"));
        assertThrows(IllegalArgumentException.class, () -> new LoadTestConfig(5, 10, 101, 10, "path"));
        assertThrows(IllegalArgumentException.class, () -> new LoadTestConfig(5, 10, 50, 0, "path"));
    }

    @Test
    void testReportGenerationContent() {
        LoadTestConfig config = new LoadTestConfig(5, 20, 50, 10, "docs/load_test_report.txt");
        List<ServerAddress> servers = List.of(
                new ServerAddress("127.0.0.1", 5001),
                new ServerAddress("127.0.0.1", 5002)
        );

        String report = LoadTester.generateReportContent(
                config,
                1000,
                100.0,
                2.5,
                1.0,
                15.0,
                2.2,
                4.5,
                9.0,
                100,
                98,
                2,
                98.0,
                50,
                0,
                48,
                2,
                45,
                3,
                servers
        );

        assertNotNull(report);
        assertTrue(report.contains("LOAD TEST REPORT"));
        assertTrue(report.contains("Issue trien khai        : #17 Load tester"));
        assertTrue(report.contains("So luong luong dong thoi (N threads)   : 5"));
        assertTrue(report.contains("So request tren moi luong (M requests)  : 20"));
        assertTrue(report.contains("Tong so request thuc te                : 100"));
        assertTrue(report.contains("Throughput dat duoc                    : 100.00 req/s"));
        assertTrue(report.contains("Ty le thanh cong (Success Rate)        : 98.00 %"));
        assertTrue(report.contains("Do tre trung binh (Average Latency)    : 2.50 ms"));
        assertTrue(report.contains("127.0.0.1:5001"));
        assertTrue(report.contains("127.0.0.1:5002"));
    }

    @Test
    void testWriteReportToFileOverwritesExisting(@TempDir Path tempDir) throws IOException {
        Path reportFile = tempDir.resolve("test_report.txt");

        // Ghi lan 1
        String firstContent = "FIRST CONTENT 12345";
        LoadTester.writeReportToFile(firstContent, reportFile.toString());
        assertEquals(firstContent, Files.readString(reportFile));

        // Ghi de lan 2 (phai ghi de toan bo, khong noi them)
        String secondContent = "SECOND CONTENT OVERWRITE";
        LoadTester.writeReportToFile(secondContent, reportFile.toString());
        assertEquals(secondContent, Files.readString(reportFile));
        assertFalse(Files.readString(reportFile).contains("FIRST"));
    }

    @Test
    void testConcurrentExecutionWithRealServer(@TempDir Path tempDir) throws Exception {
        // Bat CacheServer that tren port 0 theo dung quy uoc du an
        CacheServer server = new CacheServer(0, 60, 1000);
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        assertTrue(server.awaitListening(3000), "Server phai san sang lang nghe");
        int port = server.localPort();

        Path reportFile = tempDir.resolve("load_test_report.txt");

        try {
            CacheClient client = new CacheClient(List.of(new ServerAddress("127.0.0.1", port)));

            // 4 luong x 10 request = 40 requests, 50% PUT / 50% GET
            LoadTestConfig config = new LoadTestConfig(4, 10, 50, 10, reportFile.toString());

            LoadTestResult result = LoadTester.runTest(client, config);

            assertNotNull(result);
            assertEquals(4, result.threads());
            assertEquals(10, result.requestsPerThread());
            assertEquals(40, result.totalRequests());
            assertEquals(40, result.successCount(), "Tat ca request toi server that phai thanh cong");
            assertEquals(0, result.errorCount());
            assertEquals(100.0, result.successRate());
            assertTrue(result.throughput() > 0);
            assertTrue(result.avgLatencyMs() > 0);
            assertTrue(result.minLatencyMs() > 0);
            assertTrue(result.maxLatencyMs() >= result.minLatencyMs());

            // Kiem tra file bao cao duoc tao va chua noi dung
            assertTrue(Files.exists(reportFile));
            String fileContent = Files.readString(reportFile);
            assertTrue(fileContent.contains("KEYNET DISTRIBUTED CACHE - LOAD TEST REPORT"));
            assertTrue(fileContent.contains("100.00 %"));
        } finally {
            server.shutdown();
        }
    }
}
