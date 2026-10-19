package com.nhom05.cache.gui;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * Cua so chinh cua ung dung Cluster Dashboard.
 * Chay truc tiep bang:
 *   java -cp target/keynet.jar com.nhom05.cache.gui.DashboardFrame [duong_dan_config]
 */
public class DashboardFrame extends JFrame {

    private final DashboardPanel dashboardPanel;

    public DashboardFrame(ServerConfig config, RegistryClient registryClient) {
        super("KeyNet — Cluster Dashboard (Nhóm 05)");

        this.dashboardPanel = new DashboardPanel(config, registryClient);
        setContentPane(dashboardPanel);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 680);
        setMinimumSize(new java.awt.Dimension(800, 500));
        setLocationRelativeTo(null);

        // Dung timer khi dong cua so
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dashboardPanel.stopAutoRefresh();
            }
        });
    }

    public DashboardPanel dashboardPanel() {
        return dashboardPanel;
    }

    public static void main(String[] args) {
        // Set Look and Feel he thong cho giao dien tu nhien
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        String configPath = (args.length > 0) ? args[0] : "config/config.properties";

        ServerConfig config;
        try {
            config = ServerConfig.load(configPath);
        } catch (IOException e) {
            System.err.println("Khong doc duoc " + configPath + ", dung cau hinh mac dinh 3 server: " + e.getMessage());
            // Tao cau hinh mac dinh neu file chua ton tai
            config = ServerConfig.of(
                    java.util.List.of(
                            new ServerConfig.ServerAddress("127.0.0.1", 5001),
                            new ServerConfig.ServerAddress("127.0.0.1", 5002),
                            new ServerConfig.ServerAddress("127.0.0.1", 5003)
                    ),
                    0
            );
        }

        RegistryClient registryClient = new RegistryClient(config.registry().host(), config.registry().port());

        final ServerConfig finalConfig = config;
        SwingUtilities.invokeLater(() -> {
            DashboardFrame frame = new DashboardFrame(finalConfig, registryClient);
            frame.setVisible(true);
        });
    }
}
