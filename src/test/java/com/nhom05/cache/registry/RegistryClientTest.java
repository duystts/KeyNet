package com.nhom05.cache.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryClientTest {

    private RegistryServer server;
    private int port;
    private RegistryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new RegistryServer(0);
        server.start();
        port = server.localPort();
        client = new RegistryClient("127.0.0.1", port);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testSendHeartbeatSuccess() {
        assertTrue(client.sendHeartbeat(0, "127.0.0.1", 5001, 10, 100));
        assertEquals("UP", client.status(0));

        // Gui overload 3 tham so
        assertTrue(client.sendHeartbeat(1, "127.0.0.1", 5002));
        assertEquals("UP", client.status(1));
    }

    @Test
    void testSendHeartbeatWhenRegistryDown() {
        server.shutdown();
        assertFalse(client.sendHeartbeat(0, "127.0.0.1", 5001, 10, 100));
    }

    @Test
    void testStatusDistinguishesDownAndUnreachable() {
        // Node chua tung heartbeat -> tra ve "DOWN" (vi Registry van dang song)
        assertEquals("DOWN", client.status(99));

        // Node da heartbeat -> "UP"
        client.sendHeartbeat(0, "127.0.0.1", 5001, 5, 50);
        assertEquals("UP", client.status(0));

        // Tat Registry -> status phai tra ve null (khong the ket noi)
        server.shutdown();
        assertNull(client.status(0));
        assertNull(client.status(99));
    }

    @Test
    void testStatsParsing() {
        // Ban dau chua co node nao -> danh sach rong
        List<RegistryClient.NodeStat> statsBefore = client.stats();
        assertNotNull(statsBefore);
        assertTrue(statsBefore.isEmpty());

        // Dang ky 2 node
        client.sendHeartbeat(0, "127.0.0.1", 5001, 12, 340);
        client.sendHeartbeat(1, "127.0.0.1", 5002, 25, 500);

        List<RegistryClient.NodeStat> stats = client.stats();
        assertEquals(2, stats.size());

        RegistryClient.NodeStat node0 = stats.get(0);
        assertEquals(0, node0.index());
        assertTrue(node0.up());
        assertEquals(12, node0.keyCount());
        assertEquals(340, node0.requestCount());

        RegistryClient.NodeStat node1 = stats.get(1);
        assertEquals(1, node1.index());
        assertTrue(node1.up());
        assertEquals(25, node1.keyCount());
        assertEquals(500, node1.requestCount());
    }

    @Test
    void testStatsWhenRegistryDown() {
        server.shutdown();
        List<RegistryClient.NodeStat> stats = client.stats();
        assertNotNull(stats);
        assertTrue(stats.isEmpty());
    }

    @Test
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new RegistryClient(null, 6000));
        assertThrows(IllegalArgumentException.class, () -> new RegistryClient("", 6000));
        assertThrows(IllegalArgumentException.class, () -> new RegistryClient("127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class, () -> new RegistryClient("127.0.0.1", 70000));
    }
}
