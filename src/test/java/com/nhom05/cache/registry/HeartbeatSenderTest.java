package com.nhom05.cache.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatSenderTest {

    private RegistryServer server;
    private int registryPort;
    private HeartbeatSender sender;

    @BeforeEach
    void setUp() throws IOException {
        server = new RegistryServer(0);
        server.start();
        registryPort = server.localPort();
    }

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.stop();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testPeriodicHeartbeatAndStatsUpdate() throws InterruptedException {
        AtomicInteger keys = new AtomicInteger(5);
        AtomicLong requests = new AtomicLong(100);

        // interval 1 giay
        sender = new HeartbeatSender("127.0.0.1", registryPort, 0, "127.0.0.1", 5001, 1,
                keys::get, requests::get);

        sender.start();

        // Heartbeat duoc gui ngay lan dau (initial delay = 0)
        Thread.sleep(200);

        RegistryClient client = new RegistryClient("127.0.0.1", registryPort);
        assertEquals("UP", client.status(0));

        List<RegistryClient.NodeStat> stats = client.stats();
        assertEquals(1, stats.size());
        assertEquals(5, stats.get(0).keyCount());
        assertEquals(100, stats.get(0).requestCount());

        // Cap nhat so lieu moi (ke ca ve 0)
        keys.set(0);
        requests.set(200);

        // Cho chu ky tiep theo (1s)
        Thread.sleep(1200);

        List<RegistryClient.NodeStat> updatedStats = client.stats();
        assertEquals(1, updatedStats.size());
        assertEquals(0, updatedStats.get(0).keyCount());
        assertEquals(200, updatedStats.get(0).requestCount());
    }

    @Test
    void testSendOnceDirectly() {
        AtomicInteger keys = new AtomicInteger(42);
        AtomicLong requests = new AtomicLong(999);

        sender = new HeartbeatSender("127.0.0.1", registryPort, 2, "127.0.0.1", 5003, 3,
                keys::get, requests::get);

        assertTrue(sender.sendOnce());

        RegistryClient client = new RegistryClient("127.0.0.1", registryPort);
        assertEquals("UP", client.status(2));

        // Registry tat -> sendOnce tra ve false, khong nem exception
        server.shutdown();
        assertFalse(sender.sendOnce());
    }

    @Test
    void testStartWithoutRunningRegistryDoesNotCrash() {
        server.shutdown();

        sender = new HeartbeatSender("127.0.0.1", registryPort, 0, "127.0.0.1", 5001, 1,
                () -> 0, () -> 0);

        assertDoesNotThrow(() -> sender.start());
        assertDoesNotThrow(() -> sender.stop());
    }
}
