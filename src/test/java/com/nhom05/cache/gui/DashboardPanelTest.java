package com.nhom05.cache.gui;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardPanelTest {

    @BeforeAll
    static void initHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void testPanelCreationAndUpdateView() {
        ServerConfig config = ServerConfig.of(
                List.of(
                        new ServerConfig.ServerAddress("127.0.0.1", 5001),
                        new ServerConfig.ServerAddress("127.0.0.1", 5002)
                ),
                0
        );

        RegistryClient client = new RegistryClient("127.0.0.1", 6000);

        DashboardPanel panel = new DashboardPanel(config, client);
        assertNotNull(panel);

        // Update view truc tiep voi model
        List<NodeDisplayInfo> nodes = List.of(
                new NodeDisplayInfo(0, "127.0.0.1", 5001, 6001, DashboardDataModel.STATUS_UP, true, 12, 100, 1),
                new NodeDisplayInfo(1, "127.0.0.1", 5002, 6002, DashboardDataModel.STATUS_DOWN, false, 0, 0, 0)
        );

        DashboardDataModel model = new DashboardDataModel(true, "127.0.0.1:6000", nodes, 2, 1, 12, 100, "10:00:00");
        assertDoesNotThrow(() -> panel.updateView(model));

        // Test update view khi mat ket noi Registry
        DashboardDataModel disconnectedModel = DashboardDataModel.build(config, List.of(), false, "127.0.0.1:6000", "10:01:00");
        assertDoesNotThrow(() -> panel.updateView(disconnectedModel));

        // Dung timer
        panel.stopAutoRefresh();
    }
}
