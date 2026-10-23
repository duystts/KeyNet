package com.nhom05.cache.gui;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;
import com.nhom05.cache.registry.RegistryServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardDataModelTest {

    private ServerConfig config;

    @BeforeEach
    void setUp() {
        config = ServerConfig.of(
                List.of(
                        new ServerConfig.ServerAddress("127.0.0.1", 5001),
                        new ServerConfig.ServerAddress("127.0.0.1", 5002),
                        new ServerConfig.ServerAddress("127.0.0.1", 5003)
                ),
                0
        );
    }

    @Test
    void testBuildWithActiveNodes() {
        List<RegistryClient.NodeStat> stats = List.of(
                new RegistryClient.NodeStat(0, true, 15, 120),
                new RegistryClient.NodeStat(1, true, 8, 45),
                new RegistryClient.NodeStat(2, false, 0, 10)
        );

        DashboardDataModel model = DashboardDataModel.build(config, stats, true, "127.0.0.1:6000", "12:00:00");

        assertTrue(model.isRegistryConnected());
        assertEquals("127.0.0.1:6000", model.registryAddress());
        assertEquals(3, model.totalNodes());
        assertEquals(2, model.upNodes()); // node 0 va 1 UP
        assertEquals(23, model.totalKeys()); // 15 + 8
        assertEquals(165, model.totalRequests()); // 120 + 45 (node UP)

        assertEquals(3, model.nodes().size());

        NodeDisplayInfo n0 = model.nodes().get(0);
        assertEquals(0, n0.index());
        assertEquals(DashboardDataModel.STATUS_UP, n0.status());
        assertTrue(n0.isUp());
        assertEquals(15, n0.keyCount());
        assertEquals(120, n0.requestCount());
        assertEquals(6001, n0.replicationPort());
        assertEquals(2, n0.backupForIndex()); // (0 - 1 + 3) % 3 = 2

        NodeDisplayInfo n2 = model.nodes().get(2);
        assertEquals(DashboardDataModel.STATUS_DOWN, n2.status());
        assertFalse(n2.isUp());
    }

    @Test
    void testWithNodesDownGhiDeTrangThaiRegistryChuaCapNhat() {
        // #61: vua tat #0 tu GUI, Registry chua het timeout nen van bao UP
        List<RegistryClient.NodeStat> stats = List.of(
                new RegistryClient.NodeStat(0, true, 15, 120),
                new RegistryClient.NodeStat(1, true, 8, 45),
                new RegistryClient.NodeStat(2, true, 5, 30)
        );
        DashboardDataModel model = DashboardDataModel.build(config, stats, true, "127.0.0.1:6000", "12:00:00")
                .withNodesDown(java.util.Set.of(0));

        NodeDisplayInfo n0 = model.nodes().get(0);
        assertEquals(DashboardDataModel.STATUS_DOWN, n0.status());
        assertFalse(n0.isUp());
        assertTrue(model.nodes().get(1).isUp());
        assertEquals(2, model.upNodes());
        assertEquals(13, model.totalKeys());       // 8 + 5, khong tinh node da tat
        assertEquals(75, model.totalRequests());   // 45 + 30
        assertEquals("12:00:00", model.lastUpdatedTime());

        // Khong co node nao bi tat -> giu nguyen
        DashboardDataModel same = DashboardDataModel.build(config, stats, true, "127.0.0.1:6000", "12:00:00")
                .withNodesDown(java.util.Set.of());
        assertEquals(3, same.upNodes());
    }

    @Test
    void testBuildWhenRegistryDisconnected() {
        // Khi Registry mat ket noi, toan bo node phai the hien ro trang thai mat ket noi
        DashboardDataModel model = DashboardDataModel.build(config, List.of(), false, "127.0.0.1:6000", "12:00:00");

        assertFalse(model.isRegistryConnected());
        assertEquals(3, model.totalNodes());
        assertEquals(0, model.upNodes());
        assertEquals(0, model.totalKeys());
        assertEquals(0, model.totalRequests());

        for (NodeDisplayInfo node : model.nodes()) {
            assertEquals(DashboardDataModel.STATUS_DISCONNECTED, node.status());
            assertFalse(node.isUp());
        }
    }

    @Test
    void testBuildWithUnregisteredNodes() {
        // Registry song nhung chi co server 0 gui heartbeat, server 1 va 2 chua gui
        List<RegistryClient.NodeStat> stats = List.of(
                new RegistryClient.NodeStat(0, true, 5, 20)
        );

        DashboardDataModel model = DashboardDataModel.build(config, stats, true, "127.0.0.1:6000", "12:00:00");

        assertTrue(model.isRegistryConnected());
        assertEquals(1, model.upNodes());
        assertEquals(3, model.totalNodes());

        assertEquals(DashboardDataModel.STATUS_UP, model.nodes().get(0).status());
        assertEquals(DashboardDataModel.STATUS_NOT_REGISTERED, model.nodes().get(1).status());
        assertEquals(DashboardDataModel.STATUS_NOT_REGISTERED, model.nodes().get(2).status());
    }

    @Test
    void testFetchWithRealRegistryServer() throws IOException {
        RegistryServer server = new RegistryServer(0);
        server.start();
        int regPort = server.localPort();

        try {
            ServerConfig testConfig = ServerConfig.of(
                    List.of(
                            new ServerConfig.ServerAddress("127.0.0.1", 5001),
                            new ServerConfig.ServerAddress("127.0.0.1", 5002)
                    ),
                    0, 60, 1000
            );

            RegistryClient client = new RegistryClient("127.0.0.1", regPort);
            // Ban dau chua co heartbeat
            DashboardDataModel m1 = DashboardDataModel.fetch(testConfig, client);
            assertTrue(m1.isRegistryConnected());
            assertEquals(0, m1.upNodes());
            assertEquals(2, m1.totalNodes());

            // Server 0 gui heartbeat
            client.sendHeartbeat(0, "127.0.0.1", 5001, 10, 50);

            DashboardDataModel m2 = DashboardDataModel.fetch(testConfig, client);
            assertTrue(m2.isRegistryConnected());
            assertEquals(1, m2.upNodes());
            assertEquals(10, m2.totalKeys());
            assertEquals(50, m2.totalRequests());
            assertEquals(DashboardDataModel.STATUS_UP, m2.nodes().get(0).status());
            assertEquals(DashboardDataModel.STATUS_NOT_REGISTERED, m2.nodes().get(1).status());
        } finally {
            server.shutdown();
        }

        // Sau khi shutdown Registry, fetch phai bao mat ket noi chu khong duoc nem Exception
        RegistryClient clientDown = new RegistryClient("127.0.0.1", regPort);
        DashboardDataModel mDown = DashboardDataModel.fetch(config, clientDown);
        assertFalse(mDown.isRegistryConnected());
        assertEquals(0, mDown.upNodes());
    }
}
