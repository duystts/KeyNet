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

        DashboardPanel panel = new DashboardPanel(config, client, "config/config.properties", false);
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

        panel.stopAutoRefresh();
    }

    @Test
    void testNodeControlButtonsAndSelection() {
        ServerConfig config = ServerConfig.of(
                List.of(
                        new ServerConfig.ServerAddress("127.0.0.1", 5001),
                        new ServerConfig.ServerAddress("127.0.0.1", 5002)
                ),
                0
        );

        RegistryClient client = new RegistryClient("127.0.0.1", 6000);
        DashboardPanel panel = new DashboardPanel(config, client, "config/config.properties", false);

        assertNotNull(panel.btnCrashNode());
        assertNotNull(panel.btnRestartNode());
        assertNotNull(panel.btnStartCluster());
        assertNotNull(panel.btnStopCluster());
        assertEquals("🚀 Khởi Động Cụm", panel.btnStartCluster().getText());
        assertEquals("🛑 Tắt Tất Cả Node", panel.btnStopCluster().getText());
        assertNotNull(panel.lblSelectedNodeInfo());

        List<NodeDisplayInfo> nodes = List.of(
                new NodeDisplayInfo(0, "127.0.0.1", 5001, 6001, DashboardDataModel.STATUS_UP, true, 5, 20, 1),
                new NodeDisplayInfo(1, "127.0.0.1", 5002, 6002, DashboardDataModel.STATUS_DOWN, false, 0, 0, 0)
        );
        DashboardDataModel model = new DashboardDataModel(true, "127.0.0.1:6000", nodes, 2, 1, 5, 20, "12:00:00");
        panel.updateView(model);

        // Chon dong 0
        panel.serverTable().setRowSelectionInterval(0, 0);
        String selectedText = panel.lblSelectedNodeInfo().getText();
        assertTrue(selectedText.contains("Server #0"));
        assertTrue(selectedText.contains("5001"));
        assertTrue(selectedText.contains("6001"));

        // Chon dong 1
        panel.serverTable().setRowSelectionInterval(1, 1);
        String selectedText1 = panel.lblSelectedNodeInfo().getText();
        assertTrue(selectedText1.contains("Server #1"));
        assertTrue(selectedText1.contains("5002"));

        panel.stopAutoRefresh();
    }

    @Test
    void testSelectionPreservedAcrossViewUpdates() {
        ServerConfig config = ServerConfig.of(
                List.of(
                        new ServerConfig.ServerAddress("127.0.0.1", 5001),
                        new ServerConfig.ServerAddress("127.0.0.1", 5002)
                ),
                0
        );

        RegistryClient client = new RegistryClient("127.0.0.1", 6000);
        DashboardPanel panel = new DashboardPanel(config, client, "config/config.properties", false);

        List<NodeDisplayInfo> nodes = List.of(
                new NodeDisplayInfo(0, "127.0.0.1", 5001, 6001, DashboardDataModel.STATUS_UP, true, 5, 20, 1),
                new NodeDisplayInfo(1, "127.0.0.1", 5002, 6002, DashboardDataModel.STATUS_DOWN, false, 0, 0, 0)
        );
        panel.updateView(new DashboardDataModel(true, "127.0.0.1:6000", nodes, 2, 1, 5, 20, "12:00:00"));

        // Chon Server #0
        panel.serverTable().setRowSelectionInterval(0, 0);
        assertEquals(0, panel.serverTable().getSelectedRow());

        // Cap nhat du lieu moi (vi du so lieu key tang len sau 2 giay)
        List<NodeDisplayInfo> updatedNodes = List.of(
                new NodeDisplayInfo(0, "127.0.0.1", 5001, 6001, DashboardDataModel.STATUS_UP, true, 15, 50, 1),
                new NodeDisplayInfo(1, "127.0.0.1", 5002, 6002, DashboardDataModel.STATUS_DOWN, false, 0, 0, 0)
        );
        panel.updateView(new DashboardDataModel(true, "127.0.0.1:6000", updatedNodes, 2, 1, 15, 50, "12:00:02"));

        // Selection phai van duoc giu nguyen la Server #0 (khong bi mat do fireTableDataChanged)
        assertEquals(0, panel.serverTable().getSelectedRow(), "Selection tren bang phai duoc bao toan sau khi cap nhat du lieu");
        assertTrue(panel.lblSelectedNodeInfo().getText().contains("Server #0"));

        panel.stopAutoRefresh();
    }
}
