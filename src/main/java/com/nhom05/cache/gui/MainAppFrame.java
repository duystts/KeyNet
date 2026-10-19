package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * Cua so tich hop tong the cua he thong KeyNet (Module 5):
 * - Tab 1: Dashboard giam sat cum Cache Server va Registry (#14)
 * - Tab 2: Cache Client thao tac PUT/GET/DELETE/INFO va Key Explorer (#15)
 * - Tab 3: Kiem thu tai cum Cache Server voi N luong x M request (#17)
 *
 * Chay truc tiep bang:
 *   java -cp target/keynet.jar com.nhom05.cache.gui.MainAppFrame [duong_dan_config]
 */
public class MainAppFrame extends JFrame {

    private final DashboardPanel dashboardPanel;
    private final ClientPanel clientPanel;
    private final LoadTesterPanel loadTesterPanel;

    public MainAppFrame(ServerConfig config, RegistryClient registryClient, CacheClient cacheClient) {
        this(config, registryClient, cacheClient, "config/config.properties");
    }

    public MainAppFrame(ServerConfig config, RegistryClient registryClient, CacheClient cacheClient, String configPath) {
        super("KeyNet — Hệ Thống Cache Phân Tán (Nhóm 05)");

        this.dashboardPanel = new DashboardPanel(config, registryClient, configPath);
        this.clientPanel = new ClientPanel(cacheClient);
        this.loadTesterPanel = new LoadTesterPanel(cacheClient);

        JTabbedPane rootTabbedPane = new JTabbedPane();
        rootTabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 13));

        rootTabbedPane.addTab("Giám Sát Cụm (Dashboard #14)", dashboardPanel);
        rootTabbedPane.addTab("Thao Tác Cache & Key Explorer (#15)", clientPanel);
        rootTabbedPane.addTab("Kiểm Thử Tải (Load Tester #17)", loadTesterPanel);

        setContentPane(rootTabbedPane);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1080, 740);
        setMinimumSize(new Dimension(880, 560));
        setLocationRelativeTo(null);

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

    public ClientPanel clientPanel() {
        return clientPanel;
    }

    public LoadTesterPanel loadTesterPanel() {
        return loadTesterPanel;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        String configPath = (args.length > 0) ? args[0] : "config/config.properties";

        ServerConfig config;
        try {
            config = ServerConfig.load(configPath);
        } catch (IOException e) {
            System.err.println("Khong doc duoc " + configPath + ", dung cau hinh mac dinh: " + e.getMessage());
            config = ServerConfig.of(
                    java.util.List.of(
                            new ServerConfig.ServerAddress("127.0.0.1", 5001),
                            new ServerConfig.ServerAddress("127.0.0.1", 5002),
                            new ServerConfig.ServerAddress("127.0.0.1", 5003)
                    ),
                    0
            );
        }

        RegistryClient regClient = new RegistryClient(config.registry().host(), config.registry().port());
        CacheClient cacheClient = new CacheClient(config.servers());

        final ServerConfig finalConfig = config;
        final String finalConfigPath = configPath;
        SwingUtilities.invokeLater(() -> {
            MainAppFrame frame = new MainAppFrame(finalConfig, regClient, cacheClient, finalConfigPath);
            frame.setVisible(true);
        });
    }
}
