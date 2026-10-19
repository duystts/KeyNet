package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig;

import javax.swing.*;
import java.io.IOException;

/**
 * Cua so doc lap chay rieng GUI Client thao tac voi Cache Server.
 * Chay truc tiep bang:
 *   java -cp target/keynet.jar com.nhom05.cache.gui.ClientFrame [duong_dan_config]
 */
public class ClientFrame extends JFrame {

    private final ClientPanel clientPanel;

    public ClientFrame(CacheClient client) {
        super("KeyNet — Cache Client & Key Explorer (Nhóm 05)");

        this.clientPanel = new ClientPanel(client);
        setContentPane(clientPanel);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 680);
        setMinimumSize(new java.awt.Dimension(800, 520));
        setLocationRelativeTo(null);
    }

    public ClientPanel clientPanel() {
        return clientPanel;
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
            System.err.println("Khong doc duoc " + configPath + ", dung cau hinh mac dinh: " + e.getMessage());
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
            ClientFrame frame = new ClientFrame(finalClient);
            frame.setVisible(true);
        });
    }
}
