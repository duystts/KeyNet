package com.nhom05.cache.gui;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;
import com.nhom05.cache.registry.RegistryServer;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Module 5 - Panel Dashboard giam sat trang thai cum Cache Server tu Registry.
 *
 * Tuan thu dac ta:
 * - Cap nhat moi 1-2 giay tu RegistryClient.stats()
 * - Hien thi du N server theo ServerConfig (ke ca chua co trong STATS)
 * - Khong lam treo giao dien khi Registry chua chay (chay tren SwingWorker)
 * - TitledBorder va JScrollPane co mau nen ro rang, khong bi chu trang nen sang
 */
public class DashboardPanel extends JPanel {

    private static final int DEFAULT_REFRESH_INTERVAL_MS = 2000;

    private static final Color BG_MAIN = new Color(245, 247, 250);
    private static final Color BG_CARD = Color.WHITE;
    private static final Color COLOR_PRIMARY = new Color(33, 150, 243);
    private static final Color COLOR_SUCCESS_BG = new Color(232, 245, 233);
    private static final Color COLOR_SUCCESS_TEXT = new Color(46, 125, 50);
    private static final Color COLOR_DANGER_BG = new Color(255, 235, 238);
    private static final Color COLOR_DANGER_TEXT = new Color(198, 40, 40);
    private static final Color COLOR_WARN_BG = new Color(255, 243, 224);
    private static final Color COLOR_WARN_TEXT = new Color(230, 81, 0);
    private static final Color COLOR_NEUTRAL_BG = new Color(238, 238, 238);
    private static final Color COLOR_NEUTRAL_TEXT = new Color(97, 97, 97);
    private static final Color COLOR_BORDER = new Color(224, 224, 224);

    private final ServerConfig config;
    private final RegistryClient registryClient;
    private final Timer refreshTimer;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    // Cac thanh phan UI
    private JLabel registryBadge;
    private JLabel clusterStatusCardValue;
    private JLabel totalKeysCardValue;
    private JLabel totalRequestsCardValue;
    private JLabel lastUpdatedCardValue;
    private JProgressBar clusterHealthBar;

    private ServerTableModel tableModel;
    private JTable serverTable;
    private JTextArea logArea;
    private JCheckBox autoRefreshCheckBox;
    private JButton refreshButton;
    private JLabel statusFeedbackLabel;

    // Issue #16: Nut tat / bat server that (Node Control)
    private final String configPath;
    private JLabel lblSelectedNodeInfo;
    private JButton btnStartCluster;
    private JButton btnStopCluster;
    private JButton btnCrashNode;
    private JButton btnRestartNode;

    // Theo doi trang thai truoc do de ghi log su kien
    private final Map<Integer, String> previousNodeStatuses = new HashMap<>();
    private Boolean previousRegistryConnected = null;

    /**
     * #61: node vua tat thanh cong tu GUI -> thoi diem tat. Registry con bao UP toi 9 giay nen
     * refresh van hien DOWN cho toi khi Registry bat kip, node mo lai port, hoac het han.
     */
    private final Map<Integer, Long> stoppedByGui = new ConcurrentHashMap<>();
    static final long STOPPED_OVERRIDE_MS = RegistryServer.TIMEOUT_MS + 3000;
    /**
     * SHUTDOWN tra OK roi moi dung tien trinh nen ngay sau do port client van con mo; chi coi
     * "port mo lai" la node da duoc bat lai khi da qua khoang nay.
     */
    static final long RESTART_GRACE_MS = 2000;

    public DashboardPanel(ServerConfig config, RegistryClient registryClient) {
        this(config, registryClient, "config/config.properties", true);
    }

    public DashboardPanel(ServerConfig config, RegistryClient registryClient, String configPath) {
        this(config, registryClient, configPath, true);
    }

    public DashboardPanel(ServerConfig config, RegistryClient registryClient, String configPath, boolean autoStartRefresh) {
        this.config = config;
        this.registryClient = registryClient;
        this.configPath = (configPath != null && !configPath.isBlank()) ? configPath : "config/config.properties";

        setLayout(new BorderLayout(12, 12));
        setBackground(BG_MAIN);
        setBorder(new EmptyBorder(14, 14, 14, 14));

        initUI();

        // Timer dinh ky 2 giay, chay tren EDT de goi SwingWorker
        this.refreshTimer = new Timer(DEFAULT_REFRESH_INTERVAL_MS, e -> refresh());
        if (autoStartRefresh) {
            this.refreshTimer.start();
            // Nap du lieu lan dau
            refresh();
        }
    }

    private void initUI() {
        // 1. Header & Registry Banner
        add(createHeaderPanel(), BorderLayout.NORTH);

        // 2. Center: Overview Cards + Server Table + Logs
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setOpaque(false);

        // KPI Cards o tren
        centerPanel.add(createKpiCardsPanel(), BorderLayout.NORTH);

        // SplitPane giua Bang Server va Log su kien
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                createServerTableSection(), createLogSection());
        splitPane.setResizeWeight(0.65);
        splitPane.setDividerSize(6);
        splitPane.setBorder(null);
        splitPane.setOpaque(false);

        centerPanel.add(splitPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // 3. Footer: Thanh dieu khien
        add(createFooterControlPanel(), BorderLayout.SOUTH);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(10, 10));
        header.setOpaque(false);

        JPanel titleBox = new JPanel(new GridLayout(2, 1, 0, 2));
        titleBox.setOpaque(false);

        JLabel titleLabel = new JLabel("KeyNet — Cluster Dashboard");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        titleLabel.setForeground(new Color(33, 33, 33));

        JLabel subLabel = new JLabel("Giám sát trạng thái cụm Cache Server và phân phối tải từ Registry");
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subLabel.setForeground(new Color(117, 117, 117));

        titleBox.add(titleLabel);
        titleBox.add(subLabel);
        header.add(titleBox, BorderLayout.WEST);

        // Badge trang thai Registry
        registryBadge = new JLabel("● Đang kiểm tra Registry...", JLabel.CENTER);
        registryBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        registryBadge.setOpaque(true);
        registryBadge.setBackground(COLOR_NEUTRAL_BG);
        registryBadge.setForeground(COLOR_NEUTRAL_TEXT);
        registryBadge.setBorder(new CompoundBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                new EmptyBorder(6, 14, 6, 14)
        ));

        JPanel badgeWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        badgeWrapper.setOpaque(false);
        badgeWrapper.add(registryBadge);

        header.add(badgeWrapper, BorderLayout.EAST);
        return header;
    }

    private JPanel createKpiCardsPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 4, 10, 0));
        panel.setOpaque(false);

        clusterStatusCardValue = new JLabel("0 / 0 Nodes", JLabel.CENTER);
        clusterHealthBar = new JProgressBar(0, Math.max(1, config != null ? config.serverCount() : 1));
        clusterHealthBar.setValue(0);
        clusterHealthBar.setStringPainted(true);
        clusterHealthBar.setString("0% UP");
        clusterHealthBar.setPreferredSize(new Dimension(100, 16));
        clusterHealthBar.setForeground(COLOR_SUCCESS_TEXT);

        JPanel clusterCard = createCardPanel("TRẠNG THÁI CỤM", clusterStatusCardValue, clusterHealthBar);

        totalKeysCardValue = new JLabel("0", JLabel.CENTER);
        JLabel keysUnit = new JLabel("bản ghi (gồm bản sao)", JLabel.CENTER);
        keysUnit.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        keysUnit.setForeground(Color.GRAY);
        JPanel keysCard = createCardPanel("TỔNG SỐ BẢN GHI", totalKeysCardValue, keysUnit);

        totalRequestsCardValue = new JLabel("0", JLabel.CENTER);
        JLabel reqsUnit = new JLabel("requests đã xử lý", JLabel.CENTER);
        reqsUnit.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        reqsUnit.setForeground(Color.GRAY);
        JPanel reqsCard = createCardPanel("TỔNG REQUESTS", totalRequestsCardValue, reqsUnit);

        lastUpdatedCardValue = new JLabel("--:--:--", JLabel.CENTER);
        JLabel updateUnit = new JLabel("Tự động mỗi 2s", JLabel.CENTER);
        updateUnit.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        updateUnit.setForeground(Color.GRAY);
        JPanel updateCard = createCardPanel("CẬP NHẬT GẦN NHẤT", lastUpdatedCardValue, updateUnit);

        panel.add(clusterCard);
        panel.add(keysCard);
        panel.add(reqsCard);
        panel.add(updateCard);

        return panel;
    }

    private JPanel createCardPanel(String title, JLabel valueLabel, JComponent subComponent) {
        JPanel card = new JPanel(new BorderLayout(4, 6));
        card.setBackground(BG_CARD);
        card.setBorder(new CompoundBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                new EmptyBorder(10, 12, 10, 12)
        ));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        titleLabel.setForeground(new Color(117, 117, 117));
        card.add(titleLabel, BorderLayout.NORTH);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        valueLabel.setForeground(new Color(33, 33, 33));
        card.add(valueLabel, BorderLayout.CENTER);

        if (subComponent != null) {
            card.add(subComponent, BorderLayout.SOUTH);
        }

        return card;
    }

    private JPanel createServerTableSection() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_CARD);

        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                " Danh Sách Cache Server Trong Cụm ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(33, 33, 33)
        );
        panel.setBorder(new CompoundBorder(titledBorder, new EmptyBorder(6, 6, 6, 6)));

        tableModel = new ServerTableModel();
        serverTable = new JTable(tableModel);
        serverTable.setRowHeight(32);
        serverTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        serverTable.setGridColor(new Color(238, 238, 238));
        serverTable.setShowVerticalLines(true);
        serverTable.setSelectionBackground(new Color(227, 242, 253));
        serverTable.setSelectionForeground(Color.BLACK);
        serverTable.setBackground(Color.WHITE);

        // Header style
        serverTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        serverTable.getTableHeader().setBackground(new Color(245, 245, 245));
        serverTable.getTableHeader().setForeground(new Color(66, 66, 66));
        serverTable.getTableHeader().setReorderingAllowed(false);

        // Column Renderers
        StatusBadgeRenderer statusRenderer = new StatusBadgeRenderer();
        serverTable.getColumnModel().getColumn(5).setCellRenderer(statusRenderer);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        serverTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        serverTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);
        serverTable.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
        serverTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        serverTable.getColumnModel().getColumn(4).setCellRenderer(centerRenderer);

        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(JLabel.RIGHT);
        serverTable.getColumnModel().getColumn(6).setCellRenderer(rightRenderer);
        serverTable.getColumnModel().getColumn(7).setCellRenderer(rightRenderer);

        // Canh do rong cot (cot Trang thai rong 185px de khong bi cat chu)
        serverTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        serverTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        serverTable.getColumnModel().getColumn(2).setPreferredWidth(85);
        serverTable.getColumnModel().getColumn(3).setPreferredWidth(95);
        serverTable.getColumnModel().getColumn(4).setPreferredWidth(95);
        serverTable.getColumnModel().getColumn(5).setPreferredWidth(185);
        serverTable.getColumnModel().getColumn(6).setPreferredWidth(105);
        serverTable.getColumnModel().getColumn(7).setPreferredWidth(105);

        JScrollPane scrollPane = new JScrollPane(serverTable);
        // QUAN TRONG: Dat mau nen cho ca JScrollPane va Viewport de khong bi chu trang nen sang
        scrollPane.setBackground(Color.WHITE);
        scrollPane.getViewport().setBackground(Color.WHITE);
        scrollPane.setBorder(new LineBorder(COLOR_BORDER, 1));

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(createNodeControlToolbar(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createNodeControlToolbar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(6, 4, 4, 4));

        lblSelectedNodeInfo = new JLabel("Chọn một Server trong bảng để thực hiện thao tác tắt / bật node.");
        lblSelectedNodeInfo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblSelectedNodeInfo.setForeground(new Color(66, 66, 66));
        bar.add(lblSelectedNodeInfo, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);

        btnStartCluster = createStyledActionButton("🚀 Khởi Động Cụm", new Color(21, 101, 192), e -> handleStartCluster());
        btnStopCluster = createStyledActionButton("🛑 Tắt Tất Cả Node", new Color(183, 28, 28), e -> handleStopCluster());
        btnCrashNode = createStyledActionButton("Sập Node Được Chọn", new Color(198, 40, 40), e -> handleCrashNode());
        btnRestartNode = createStyledActionButton("Bật Lại Node Được Chọn", new Color(46, 125, 50), e -> handleRestartNode());

        btnPanel.add(btnStartCluster);
        btnPanel.add(btnStopCluster);
        btnPanel.add(btnCrashNode);
        btnPanel.add(btnRestartNode);
        bar.add(btnPanel, BorderLayout.EAST);

        // Lang nghe chon dong trong serverTable
        serverTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = serverTable.getSelectedRow();
                if (row >= 0 && row < tableModel.getRowCount()) {
                    NodeDisplayInfo node = tableModel.getNodeAt(row);
                    if (node != null) {
                        lblSelectedNodeInfo.setText(String.format(
                                "Đang chọn: Server #%d (%s:%d | Port Replication: %d) - Trạng thái: %s",
                                node.index(), node.host(), node.clientPort(), node.replicationPort(), node.status()
                        ));
                    }
                } else {
                    lblSelectedNodeInfo.setText("Chọn một Server trong bảng để thực hiện thao tác tắt / bật node.");
                }
            }
        });

        return bar;
    }

    private JButton createStyledActionButton(String text, Color bg, java.awt.event.ActionListener al) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed()) {
                    g2.setColor(bg.darker());
                } else if (getModel().isRollover()) {
                    g2.setColor(bg.brighter());
                } else if (!isEnabled()) {
                    g2.setColor(new Color(189, 189, 189));
                } else {
                    g2.setColor(bg);
                }
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(7, 14, 7, 14));
        btn.addActionListener(al);
        return btn;
    }

    private void handleStartCluster() {
        btnStartCluster.setEnabled(false);
        btnStopCluster.setEnabled(false);
        btnCrashNode.setEnabled(false);
        btnRestartNode.setEnabled(false);
        statusFeedbackLabel.setText("Đang khởi động cụm...");
        appendLog("[KHỞI ĐỘNG CỤM] Bắt đầu kiểm tra và khởi động các thành phần trong cụm...");

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                // 1. Kiem tra va khoi dong Registry Server
                String regHost = (config.registry() != null) ? config.registry().host() : "127.0.0.1";
                int regPort = (config.registry() != null) ? config.registry().port() : 6000;

                if (NodeProcessManager.isPortListening(regHost, regPort, 200)) {
                    publish("[REGISTRY] Registry Server (port " + regPort + ") đã đang hoạt động.");
                } else {
                    publish("[REGISTRY] Đang khởi động Registry Server trên port " + regPort + "...");
                    try {
                        NodeProcessManager.startRegistry(regPort);
                        boolean ready = false;
                        for (int attempt = 0; attempt < 15; attempt++) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            if (NodeProcessManager.isPortListening(regHost, regPort, 150)) {
                                ready = true;
                                break;
                            }
                        }
                        if (ready) {
                            publish("[REGISTRY THÀNH CÔNG] Registry Server đã sẵn sàng trên port " + regPort + " (log: logs/registry.log)!");
                        } else {
                            publish("[REGISTRY CẢNH BÁO] Registry Server chưa mở port kịp thời. Kiểm tra logs/registry.log.");
                        }
                    } catch (Exception ex) {
                        publish("[REGISTRY THẤT BẠI] Lỗi khởi động Registry Server: " + ex.getMessage());
                    }
                }

                // 2. Kiem tra va khoi dong cac Cache Server
                List<ServerConfig.ServerAddress> servers = config.servers();
                for (int i = 0; i < servers.size(); i++) {
                    ServerConfig.ServerAddress s = servers.get(i);
                    int sIndex = i;
                    if (NodeProcessManager.isPortListening(s.host(), s.port(), 200)) {
                        publish("[SERVER #" + sIndex + "] Server đã đang hoạt động (port " + s.port() + ").");
                    } else {
                        publish("[SERVER #" + sIndex + "] Đang khởi động Server #" + sIndex + " (port " + s.port() + ")...");
                        try {
                            NodeProcessManager.startNode(sIndex, configPath);
                            boolean ready = false;
                            for (int attempt = 0; attempt < 15; attempt++) {
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                if (NodeProcessManager.isPortListening(s.host(), s.port(), 150)) {
                                    ready = true;
                                    break;
                                }
                            }
                            if (ready) {
                                publish("[SERVER #" + sIndex + " THÀNH CÔNG] Server #" + sIndex + " đã sẵn sàng (port " + s.port() + ") và đang gửi heartbeat đăng ký!");
                            } else {
                                publish("[SERVER #" + sIndex + " CẢNH BÁO] Server #" + sIndex + " chưa mở port kịp thời. Kiểm tra logs/server-" + sIndex + ".log.");
                            }
                        } catch (Exception ex) {
                            publish("[SERVER #" + sIndex + " THẤT BẠI] Lỗi khởi động Server #" + sIndex + ": " + ex.getMessage());
                        }
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                    statusFeedbackLabel.setText(msg);
                }
            }

            @Override
            protected void done() {
                btnStartCluster.setEnabled(true);
                btnStopCluster.setEnabled(true);
                btnCrashNode.setEnabled(true);
                btnRestartNode.setEnabled(true);
                statusFeedbackLabel.setText("Khởi động cụm hoàn tất!");
                Timer t = new Timer(1500, ev -> refresh());
                t.setRepeats(false);
                t.start();
            }
        };
        worker.execute();
    }

    private void handleStopCluster() {
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Bạn có chắc chắn muốn dừng toàn bộ các Cache Server trong cụm?",
                "Xác nhận tắt cụm",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        btnStartCluster.setEnabled(false);
        btnStopCluster.setEnabled(false);
        btnCrashNode.setEnabled(false);
        btnRestartNode.setEnabled(false);
        statusFeedbackLabel.setText("Đang gửi lệnh dừng tới tất cả các Server...");
        appendLog("[DỪNG CỤM] Bắt đầu tắt toàn bộ các Cache Server trong cụm...");

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                List<ServerConfig.ServerAddress> servers = config.servers();
                for (int i = 0; i < servers.size(); i++) {
                    ServerConfig.ServerAddress s = servers.get(i);
                    int sIndex = i;
                    int replPort = s.port() + 1000;
                    if (NodeProcessManager.isPortListening(s.host(), s.port(), 150)) {
                        boolean ok = NodeProcessManager.shutdownNode(s.host(), replPort, 1500);
                        if (ok) {
                            publish("[SẬP NODE #" + sIndex + " THÀNH CÔNG] Server #" + sIndex + " đã xác nhận SHUTDOWN.");
                        } else {
                            Process p = NodeProcessManager.getManagedProcess(sIndex);
                            if (p != null && p.isAlive()) {
                                p.destroy();
                                publish("[SẬP NODE #" + sIndex + "] Đã dừng tiến trình Server #" + sIndex + ".");
                            } else {
                                publish("[SẬP NODE #" + sIndex + " THẤT BẠI] Không thể kết nối cổng replication của Server #" + sIndex + ".");
                            }
                        }
                    } else {
                        publish("[SẬP NODE #" + sIndex + "] Server #" + sIndex + " đã dừng từ trước.");
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                for (int i = 0; i < config.servers().size(); i++) {
                    markStopped(i);
                }
                btnStartCluster.setEnabled(true);
                btnStopCluster.setEnabled(true);
                btnCrashNode.setEnabled(true);
                btnRestartNode.setEnabled(true);
                statusFeedbackLabel.setText("Đã dừng toàn bộ các Cache Server.");
                appendLog("[DỪNG CỤM HOÀN TẤT] Toàn bộ các Cache Server đã chuyển trạng thái DOWN.");
                Timer t = new Timer(1000, ev -> refresh());
                t.setRepeats(false);
                t.start();
            }
        };
        worker.execute();
    }

    private void handleCrashNode() {
        int row = serverTable.getSelectedRow();
        if (row < 0 || row >= tableModel.getRowCount()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhấp chọn một Server trong bảng trước!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        NodeDisplayInfo node = tableModel.getNodeAt(row);
        if (node == null) return;

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Bạn có chắc chắn muốn làm SẬP Server #" + node.index() + " (" + node.host() + ":" + node.clientPort() + ") không?\n"
                        + "Lệnh SHUTDOWN sẽ được gửi tới port replication " + node.replicationPort() + " để tắt tiến trình thật.",
                "Xác nhận sập node",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        btnCrashNode.setEnabled(false);
        btnRestartNode.setEnabled(false);
        btnStartCluster.setEnabled(false);
        btnStopCluster.setEnabled(false);
        statusFeedbackLabel.setText("Đang gửi lệnh SHUTDOWN tới Server #" + node.index() + "...");
        appendLog("[THAO TÁC] Gửi lệnh SHUTDOWN tới Server #" + node.index() + " (Port " + node.replicationPort() + ")...");

        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                return NodeProcessManager.shutdownNode(node.host(), node.replicationPort());
            }

            @Override
            protected void done() {
                try {
                    boolean ok = get();
                    if (ok) {
                        appendLog("[SẬP NODE THÀNH CÔNG] Server #" + node.index() + " đã xác nhận lệnh SHUTDOWN và dừng tiến trình!");
                        statusFeedbackLabel.setText("Đã sập Server #" + node.index());
                        markStopped(node.index());
                        lblSelectedNodeInfo.setText(String.format(
                                "Đang chọn: Server #%d (%s:%d | Port Replication: %d) - Trạng thái: %s",
                                node.index(), node.host(), node.clientPort(), node.replicationPort(), DashboardDataModel.STATUS_DOWN
                        ));
                    } else {
                        appendLog("[SẬP NODE THẤT BẠI] Không thể gửi SHUTDOWN tới Server #" + node.index() + " (Port " + node.replicationPort() + "). Có thể server đã dừng từ trước.");
                        statusFeedbackLabel.setText("Không thể kết nối port replication của Server #" + node.index());
                    }
                } catch (Exception e) {
                    appendLog("[SẬP NODE LỖI] " + e.getMessage());
                } finally {
                    btnCrashNode.setEnabled(true);
                    btnRestartNode.setEnabled(true);
                    btnStartCluster.setEnabled(true);
                    btnStopCluster.setEnabled(true);
                    refresh();
                }
            }
        };
        worker.execute();
    }

    private void handleRestartNode() {
        int row = serverTable.getSelectedRow();
        if (row < 0 || row >= tableModel.getRowCount()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhấp chọn một Server trong bảng trước!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        NodeDisplayInfo node = tableModel.getNodeAt(row);
        if (node == null) return;

        // Chan khi node thuc su dang mo port
        if (node.isUp() && NodeProcessManager.isPortListening(node.host(), node.clientPort(), 200)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Server #" + node.index() + " hiện đang hoạt động (UP). Không thể bật lại node đang chạy!",
                    "Cảnh báo",
                    JOptionPane.WARNING_MESSAGE
            );
            appendLog("[BẬT LẠI BỊ CHẶN] Server #" + node.index() + " hiện đang hoạt động (UP). Hãy sập node trước nếu muốn khởi động lại.");
            return;
        }

        btnCrashNode.setEnabled(false);
        btnRestartNode.setEnabled(false);
        btnStartCluster.setEnabled(false);
        btnStopCluster.setEnabled(false);
        statusFeedbackLabel.setText("Đang khởi động lại Server #" + node.index() + "...");
        appendLog("[THAO TÁC] Khởi động lại Server #" + node.index() + " qua ProcessBuilder...");

        SwingWorker<Process, Void> worker = new SwingWorker<>() {
            @Override
            protected Process doInBackground() throws Exception {
                Process p = NodeProcessManager.startNode(node.index(), configPath);
                // Cho khoang 1s de kiem tra tien trinh co chet som hay khong
                Thread.sleep(1000);
                if (!p.isAlive()) {
                    String lastLog = NodeProcessManager.readLastLogLine(node.index());
                    String msg = "Tiến trình dừng ngay sau khi bật (exitCode: " + p.exitValue() + ").";
                    if (lastLog != null && !lastLog.isBlank()) {
                        msg += " Dòng cuối log: " + lastLog;
                    }
                    throw new java.io.IOException(msg);
                }
                return p;
            }

            @Override
            protected void done() {
                try {
                    Process p = get();
                    stoppedByGui.remove(node.index());
                    appendLog("[BẬT LẠI NODE THÀNH CÔNG] Đã khởi chạy Server #" + node.index() + " (PID: " + p.pid() + ", log: logs/server-" + node.index() + ".log). Server đang tự SYNC dữ liệu...");
                    statusFeedbackLabel.setText("Đã khởi chạy Server #" + node.index());
                    Timer t = new Timer(1500, e -> refresh());
                    t.setRepeats(false);
                    t.start();
                } catch (Exception e) {
                    Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                    appendLog("[BẬT LẠI THẤT BẠI] " + cause.getMessage());
                    statusFeedbackLabel.setText("Lỗi khởi chạy: " + cause.getMessage());
                    JOptionPane.showMessageDialog(
                            DashboardPanel.this,
                            "Không thể khởi chạy Server #" + node.index() + ":\n" + cause.getMessage(),
                            "Lỗi khởi chạy",
                            JOptionPane.ERROR_MESSAGE
                    );
                } finally {
                    btnCrashNode.setEnabled(true);
                    btnRestartNode.setEnabled(true);
                    btnStartCluster.setEnabled(true);
                    btnStopCluster.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private JPanel createLogSection() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(BG_CARD);

        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                " Nhật Ký Sự Kiện Cụm (Realtime Event Log) ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(33, 33, 33)
        );
        panel.setBorder(new CompoundBorder(titledBorder, new EmptyBorder(4, 6, 6, 6)));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(new Color(250, 250, 250));
        logArea.setForeground(new Color(33, 33, 33));
        logArea.setMargin(new Insets(6, 8, 6, 8));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBackground(Color.WHITE);
        logScroll.getViewport().setBackground(new Color(250, 250, 250));
        logScroll.setBorder(new LineBorder(COLOR_BORDER, 1));

        JPanel logToolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        logToolBar.setOpaque(false);
        JButton clearLogBtn = new JButton("Xóa nhật ký");
        clearLogBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        clearLogBtn.setFocusPainted(false);
        clearLogBtn.addActionListener(e -> logArea.setText(""));
        logToolBar.add(clearLogBtn);

        panel.add(logToolBar, BorderLayout.NORTH);
        panel.add(logScroll, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createFooterControlPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setOpaque(false);

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftPanel.setOpaque(false);

        refreshButton = new JButton("🔄 Làm mới ngay");
        refreshButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        refreshButton.setBackground(Color.WHITE);
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refresh());

        autoRefreshCheckBox = new JCheckBox("Tự động làm mới mỗi 2 giây", true);
        autoRefreshCheckBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        autoRefreshCheckBox.setOpaque(false);
        autoRefreshCheckBox.addActionListener(e -> {
            if (autoRefreshCheckBox.isSelected()) {
                refreshTimer.start();
                appendLog("Đã bật tự động làm mới mỗi 2 giây.");
            } else {
                refreshTimer.stop();
                appendLog("Đã tạm dừng tự động làm mới.");
            }
        });

        leftPanel.add(refreshButton);
        leftPanel.add(autoRefreshCheckBox);

        statusFeedbackLabel = new JLabel("Sẵn sàng");
        statusFeedbackLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        statusFeedbackLabel.setForeground(new Color(117, 117, 117));

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        rightPanel.setOpaque(false);
        rightPanel.add(statusFeedbackLabel);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.EAST);

        return panel;
    }

    /**
     * Kich hoat refresh du lieu tu Registry qua SwingWorker de khong lam treo giao dien.
     */
    public void refresh() {
        if (!isRefreshing.compareAndSet(false, true)) {
            return; // Dang co mot tac vu refresh chay
        }

        statusFeedbackLabel.setText("Đang tải dữ liệu từ Registry...");

        SwingWorker<DashboardDataModel, Void> worker = new SwingWorker<>() {
            @Override
            protected DashboardDataModel doInBackground() {
                DashboardDataModel model = DashboardDataModel.fetch(config, registryClient);
                return model.withNodesDown(nodesStillStopped(model));
            }

            @Override
            protected void done() {
                try {
                    DashboardDataModel model = get();
                    updateView(model);
                    statusFeedbackLabel.setText("Cập nhật lúc: " + model.lastUpdatedTime());
                } catch (Exception e) {
                    statusFeedbackLabel.setText("Lỗi cập nhật: " + e.getMessage());
                } finally {
                    isRefreshing.set(false);
                }
            }
        };

        worker.execute();
    }

    /**
     * Cap nhat toan bo giao dien dua tren DashboardDataModel.
     */
    public void updateView(DashboardDataModel model) {
        if (model == null) return;

        // 1. Cap nhat Registry Badge
        if (model.isRegistryConnected()) {
            registryBadge.setText("● REGISTRY: " + model.registryAddress() + " (HOẠT ĐỘNG)");
            registryBadge.setBackground(COLOR_SUCCESS_BG);
            registryBadge.setForeground(COLOR_SUCCESS_TEXT);
            registryBadge.setBorder(new CompoundBorder(
                    new LineBorder(new Color(129, 199, 132), 1, true),
                    new EmptyBorder(6, 14, 6, 14)
            ));
        } else {
            registryBadge.setText("● MẤT KẾT NỐI REGISTRY (" + model.registryAddress() + ")");
            registryBadge.setBackground(COLOR_DANGER_BG);
            registryBadge.setForeground(COLOR_DANGER_TEXT);
            registryBadge.setBorder(new CompoundBorder(
                    new LineBorder(new Color(229, 115, 115), 1, true),
                    new EmptyBorder(6, 14, 6, 14)
            ));
        }

        // Ghi log neu trang thai Registry thay doi
        if (previousRegistryConnected == null || previousRegistryConnected != model.isRegistryConnected()) {
            if (model.isRegistryConnected()) {
                appendLog("Kết nối thành công tới Registry: " + model.registryAddress());
            } else {
                appendLog("CẢNH BÁO: Không thể kết nối tới Registry (" + model.registryAddress() + ")");
            }
            previousRegistryConnected = model.isRegistryConnected();
        }

        // 2. Cap nhat KPI Cards
        clusterStatusCardValue.setText(model.upNodes() + " / " + model.totalNodes() + " Nodes");
        clusterHealthBar.setMaximum(Math.max(1, model.totalNodes()));
        clusterHealthBar.setValue(model.upNodes());
        int percent = (model.totalNodes() > 0) ? (model.upNodes() * 100 / model.totalNodes()) : 0;
        clusterHealthBar.setString(percent + "% UP");

        totalKeysCardValue.setText(String.format("%,d", model.totalKeys()));
        totalRequestsCardValue.setText(String.format("%,d", model.totalRequests()));
        lastUpdatedCardValue.setText(model.lastUpdatedTime());

        // 3. Cap nhat Bang Server va bao toan lua chon dong hien tai
        int selectedRow = serverTable.getSelectedRow();
        int selectedNodeIndex = -1;
        if (selectedRow >= 0 && selectedRow < tableModel.getRowCount()) {
            NodeDisplayInfo selectedNode = tableModel.getNodeAt(selectedRow);
            if (selectedNode != null) {
                selectedNodeIndex = selectedNode.index();
            }
        }

        tableModel.setNodes(model.nodes());

        // Phuc hoi lai selection cho node dang duoc chon
        if (selectedNodeIndex >= 0) {
            for (int r = 0; r < tableModel.getRowCount(); r++) {
                NodeDisplayInfo n = tableModel.getNodeAt(r);
                if (n != null && n.index() == selectedNodeIndex) {
                    serverTable.setRowSelectionInterval(r, r);
                    lblSelectedNodeInfo.setText(String.format(
                            "Đang chọn: Server #%d (%s:%d | Port Replication: %d) - Trạng thái: %s",
                            n.index(), n.host(), n.clientPort(), n.replicationPort(), n.status()
                    ));
                    break;
                }
            }
        }

        // Ghi log su kien chuyen trang thai cua tung node
        for (NodeDisplayInfo node : model.nodes()) {
            String prev = previousNodeStatuses.get(node.index());
            if (prev == null || !prev.equals(node.status())) {
                if (prev != null) {
                    appendLog("Server #" + node.index() + " đổi trạng thái: [" + prev + "] -> [" + node.status() + "]"
                            + (node.isUp() ? " (keys: " + node.keyCount() + ", reqs: " + node.requestCount() + ")" : ""));
                }
                previousNodeStatuses.put(node.index(), node.status());
            }
        }
    }

    /** Danh dau node da tat tu GUI: hien DOWN ngay va giu DOWN qua cac lan refresh (#61). */
    private void markStopped(int index) {
        stoppedByGui.put(index, System.currentTimeMillis());
        tableModel.markNodeDown(index);
    }

    /**
     * Chay trong luong nen cua refresh(): node nao van can ghi de DOWN. Bo ghi nho khi Registry
     * da bao khong UP, khi node mo lai port client sau RESTART_GRACE_MS (da duoc bat lai tu ngoai
     * GUI) hoac khi qua STOPPED_OVERRIDE_MS.
     */
    private Set<Integer> nodesStillStopped(DashboardDataModel model) {
        long now = System.currentTimeMillis();
        for (NodeDisplayInfo n : model.nodes()) {
            Long stoppedAt = stoppedByGui.get(n.index());
            if (stoppedAt == null) {
                continue;
            }
            long elapsed = now - stoppedAt;
            if (!n.isUp() || elapsed > STOPPED_OVERRIDE_MS || (elapsed > RESTART_GRACE_MS
                    && NodeProcessManager.isPortListening(n.host(), n.clientPort(), 200))) {
                stoppedByGui.remove(n.index(), stoppedAt);
            }
        }
        return Set.copyOf(stoppedByGui.keySet());
    }

    private void appendLog(String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.append("[" + time + "] " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public void stopAutoRefresh() {
        if (refreshTimer != null && refreshTimer.isRunning()) {
            refreshTimer.stop();
        }
    }

    public void startAutoRefresh() {
        if (refreshTimer != null && !refreshTimer.isRunning()) {
            refreshTimer.start();
        }
    }

    // =========================================================================
    // TableModel cho danh sach Cache Server
    // =========================================================================
    private static class ServerTableModel extends AbstractTableModel {

        private final String[] columns = {
                "Node", "Host", "Port Client", "Port Replication", "Dự phòng cho", "Trạng thái", "Số Key", "Số Request"
        };

        private List<NodeDisplayInfo> nodes = List.of();

        public void setNodes(List<NodeDisplayInfo> newNodes) {
            int oldSize = this.nodes.size();
            this.nodes = (newNodes != null) ? List.copyOf(newNodes) : List.of();
            if (oldSize == this.nodes.size() && oldSize > 0) {
                fireTableRowsUpdated(0, oldSize - 1);
            } else {
                fireTableDataChanged();
            }
        }

        public NodeDisplayInfo getNodeAt(int row) {
            if (row >= 0 && row < nodes.size()) {
                return nodes.get(row);
            }
            return null;
        }

        public void markNodeDown(int index) {
            List<NodeDisplayInfo> updated = new ArrayList<>(nodes);
            for (int i = 0; i < updated.size(); i++) {
                NodeDisplayInfo n = updated.get(i);
                if (n.index() == index) {
                    updated.set(i, new NodeDisplayInfo(
                            n.index(), n.host(), n.clientPort(), n.replicationPort(),
                            DashboardDataModel.STATUS_DOWN, false, n.keyCount(), n.requestCount(), n.backupForIndex()
                    ));
                    break;
                }
            }
            this.nodes = List.copyOf(updated);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return nodes.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= nodes.size()) return "";
            NodeDisplayInfo n = nodes.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> "Server #" + n.index();
                case 1 -> n.host();
                case 2 -> n.clientPort();
                case 3 -> n.replicationPort();
                case 4 -> "Server #" + n.backupForIndex();
                case 5 -> n.status();
                case 6 -> n.isUp() ? String.format("%,d", n.keyCount()) : (n.keyCount() > 0 ? String.format("%,d (lần cuối)", n.keyCount()) : "—");
                case 7 -> n.isUp() ? String.format("%,d", n.requestCount()) : (n.requestCount() > 0 ? String.format("%,d (lần cuối)", n.requestCount()) : "—");
                default -> "";
            };
        }
    }

    // =========================================================================
    // Getters ho tro kiem thu giao dien
    // =========================================================================
    public JButton btnStartCluster() {
        return btnStartCluster;
    }

    public JButton btnStopCluster() {
        return btnStopCluster;
    }

    public JButton btnCrashNode() {
        return btnCrashNode;
    }

    public JButton btnRestartNode() {
        return btnRestartNode;
    }

    public JTable serverTable() {
        return serverTable;
    }

    public JLabel lblSelectedNodeInfo() {
        return lblSelectedNodeInfo;
    }

    // =========================================================================
    // Renderer ve status dang Pill / Badge
    // =========================================================================
    private static class StatusBadgeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setHorizontalAlignment(JLabel.CENTER);
            label.setFont(new Font("Segoe UI", Font.BOLD, 12));
            label.setOpaque(true);

            String status = (value != null) ? value.toString() : "";

            if (DashboardDataModel.STATUS_UP.equals(status)) {
                label.setBackground(COLOR_SUCCESS_BG);
                label.setForeground(COLOR_SUCCESS_TEXT);
                label.setText("● UP");
            } else if (DashboardDataModel.STATUS_DOWN.equals(status)) {
                label.setBackground(COLOR_DANGER_BG);
                label.setForeground(COLOR_DANGER_TEXT);
                label.setText("● DOWN");
            } else if (DashboardDataModel.STATUS_NOT_REGISTERED.equals(status)) {
                label.setBackground(COLOR_NEUTRAL_BG);
                label.setForeground(COLOR_NEUTRAL_TEXT);
                label.setText("○ Chưa đăng ký");
            } else {
                label.setBackground(COLOR_WARN_BG);
                label.setForeground(COLOR_WARN_TEXT);
                label.setText("[!] " + status);
            }

            if (isSelected) {
                label.setBorder(new LineBorder(COLOR_PRIMARY, 1));
            } else {
                label.setBorder(new EmptyBorder(2, 6, 2, 6));
            }

            return label;
        }
    }
}
