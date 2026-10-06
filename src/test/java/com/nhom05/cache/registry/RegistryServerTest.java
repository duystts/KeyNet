package com.nhom05.cache.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryServerTest {

    private RegistryServer registry;

    @BeforeEach
    void setUp() {
        // Su dung timeout 500ms de test nhanh chet server ma khong phai cho 9 giay
        registry = new RegistryServer(0, 500L);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.shutdown();
        }
    }

    @Test
    void testHeartbeatRegistrationAndAck() {
        String res = registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");
        assertEquals("ACK", res);

        ServerNode node = registry.getServer(0);
        assertNotNull(node);
        assertEquals(0, node.getServerIndex());
        assertEquals("127.0.0.1", node.getHost());
        assertEquals(5001, node.getPort());
        assertEquals(ServerNode.Status.UP, node.getStatus());
    }

    @Test
    void testHeartbeatWithCustomMetrics() {
        String res = registry.handleCommand("HEARTBEAT|1|127.0.0.1|5002|42|100");
        assertEquals("ACK", res);

        ServerNode node = registry.getServer(1);
        assertNotNull(node);
        assertEquals(42, node.getKeyCount());
        assertEquals(100L, node.getRequestCount());

        String statsRes = registry.handleCommand("STATS");
        assertEquals("STATS|1:UP:42:100", statsRes);
    }

    @Test
    void testStatusQuery() {
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");

        // Server da dang ky va con song
        assertEquals("UP", registry.handleCommand("STATUS|0"));

        // Server chua tung dang ky
        assertEquals("DOWN", registry.handleCommand("STATUS|1"));
        assertEquals("DOWN", registry.handleCommand("STATUS|999"));
    }

    @Test
    void testStatsMultipleServersSorted() {
        registry.handleCommand("HEARTBEAT|2|127.0.0.1|5003");
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");
        registry.handleCommand("HEARTBEAT|1|127.0.0.1|5002");

        String stats = registry.handleCommand("STATS");
        // Thu tu phai duoc sap xep theo serverIndex tang dan: 0, 1, 2
        assertEquals("STATS|0:UP:0:0;1:UP:0:0;2:UP:0:0", stats);
    }

    @Test
    void testHealthCheckDetectsDeadServerAfterTimeout() throws InterruptedException {
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");
        assertEquals("UP", registry.handleCommand("STATUS|0"));

        // Doi vuot qua nguong timeout 500ms
        Thread.sleep(600);

        // Kich hoat health check
        registry.checkHealth();

        // Server phai duoc danh dau la DOWN
        ServerNode node = registry.getServer(0);
        assertEquals(ServerNode.Status.DOWN, node.getStatus());
        assertEquals("DOWN", registry.handleCommand("STATUS|0"));

        // STATS cung phai phan anh DOWN
        String stats = registry.handleCommand("STATS");
        assertTrue(stats.contains("0:DOWN:0:0"));
    }

    @Test
    void testServerRecoveryFromDeadState() throws InterruptedException {
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");
        Thread.sleep(600);
        registry.checkHealth();
        assertEquals("DOWN", registry.handleCommand("STATUS|0"));

        // Server song lai va gui lai HEARTBEAT
        String ack = registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");
        assertEquals("ACK", ack);

        ServerNode node = registry.getServer(0);
        assertEquals(ServerNode.Status.UP, node.getStatus());
        assertEquals("UP", registry.handleCommand("STATUS|0"));
    }

    @Test
    void testInvalidCommands() {
        assertTrue(registry.handleCommand("").startsWith("ERROR|E001"));
        assertTrue(registry.handleCommand(null).startsWith("ERROR|E001"));
        assertTrue(registry.handleCommand("UNKNOWN_CMD").startsWith("ERROR|E001"));
        assertTrue(registry.handleCommand("HEARTBEAT|notanumber|localhost|5001").startsWith("ERROR|E001"));
        assertTrue(registry.handleCommand("STATUS|notanumber").startsWith("ERROR|E001"));
        assertTrue(registry.handleCommand("HEARTBEAT|0").startsWith("ERROR|E001"));
    }

    @Test
    void testEndToEndTcpSocketWithRegistryClient() throws IOException, InterruptedException {
        // Tim port trong
        int testPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            testPort = socket.getLocalPort();
        }

        RegistryServer tcpRegistry = new RegistryServer(testPort, 600L);
        tcpRegistry.start();

        try {
            RegistryClient client = new RegistryClient("127.0.0.1", testPort);

            // 1. Gui Heartbeat qua TCP
            boolean hbSuccess = client.sendHeartbeat(0, "127.0.0.1", 5001, 5, 20);
            assertTrue(hbSuccess);

            // 2. Kiem tra STATUS qua TCP
            assertTrue(client.isServerUp(0));
            assertFalse(client.isServerUp(1));

            // 3. Kiem tra STATS qua TCP
            List<RegistryClient.ServerStatRecord> stats = client.getParsedStats();
            assertEquals(1, stats.size());
            assertEquals(0, stats.get(0).serverIndex());
            assertEquals(ServerNode.Status.UP, stats.get(0).status());
            assertEquals(5, stats.get(0).keyCount());
            assertEquals(20L, stats.get(0).requestCount());

            // 4. Cho timeout > 600ms de kiem tra phat hien server chet qua TCP
            Thread.sleep(700);
            tcpRegistry.checkHealth();

            assertFalse(client.isServerUp(0));
            assertEquals("DOWN", client.checkStatus(0));

        } finally {
            tcpRegistry.shutdown();
        }
    }

    @Test
    void testRestartedServerResetsMetrics() {
        // Server chet roi khoi dong lai: kho rong, bo dem request ve 0 -> dashboard phai hien 0, khong giu so cu
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001|42|100");
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001|0|0");
        assertEquals("STATS|0:UP:0:0", registry.handleCommand("STATS"));
    }

    @Test
    void testHeartbeatWithoutMetricsKeepsPreviousMetrics() {
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001|42|100");
        registry.handleCommand("HEARTBEAT|0|127.0.0.1|5001");
        assertEquals("STATS|0:UP:42:100", registry.handleCommand("STATS"));
    }
}
