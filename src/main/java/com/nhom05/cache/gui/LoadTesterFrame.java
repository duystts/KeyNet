package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig;

import javax.swing.*;
import java.io.IOException;

/**
 * Cua so doc lap chay rieng cong cu Load Tester kiem thu tai cum Cache Server.
 * Chay truc tiep bang:
 *   java -cp target/keynet.jar com.nhom05.cache.gui.LoadTesterFrame [duong_dan_config]
 */
public class LoadTesterFrame extends JFrame {

    private final LoadTesterPanel loadTesterPanel;

    public LoadTesterFrame(CacheClient client) {
        super("KeyNet — Kiểm Thử Tải Cụm Cache (Load Tester #17)");

        this.loadTesterPanel = new LoadTesterPanel(client);
        setContentPane(loadTesterPanel);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1020, 700);
        setMinimumSize(new java.awt.Dimension(850, 550));
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            setLocationRelativeTo(null);
        }
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

        CacheClient client;
        try {
            client = CacheClient.fromConfig(configPath);
        } catch (IOException e) {
            System.err.println("Khong doc duoc " + configPath + ", dung cau hinh mac dinh 3 server: " + e.getMessage());
            ServerConfig fallback = ServerConfig.of(
                    java.util.List.of(
                            new ServerConfig.ServerAddress("127.0.0.1", 5001),
                            new ServerConfig.ServerAddress("127.0.0.1", 5002),
                            new ServerConfig.ServerAddress("127.0.0.1", 5003)
                    ),
                    0
            );
            client = new CacheClient(fallback.servers());
        }

        final CacheClient finalClient = client;
        SwingUtilities.invokeLater(() -> {
            LoadTesterFrame frame = new LoadTesterFrame(finalClient);
            frame.setVisible(true);
        });
    }
}
