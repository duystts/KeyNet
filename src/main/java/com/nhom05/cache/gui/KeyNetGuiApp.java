package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;
import com.nhom05.cache.registry.ServerNode;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Module 5 — Master Edition Modern Dark Slate Dashboard & Interactive Client GUI.
 * Phụ trách: Nguyễn Văn Nhật.
 *
 * Các tính năng cao cấp tích hợp:
 * 1. 💥 1-Click Fault Injection (Mô phỏng sập server & phục hồi trực tiếp trên GUI).
 * 2. 🔍 Cache Key Explorer (Quản lý tất cả Key đang lưu trong RAM kèm thông số Primary/Replica Node).
 * 3. ⏱️ Live TTL Countdown Tracker (Bộ đếm lùi thời gian sống của bản ghi).
 * 4. 📄 Export Benchmark Report (Xuất báo cáo load test đa luồng ra file docs/load_test_report.txt).
 */
public class KeyNetGuiApp extends JFrame {

    // Modern Dark Slate Palette
    private static final Color BG_DARK = new Color(15, 23, 42);      // #0F172A
    private static final Color CARD_BG = new Color(30, 41, 59);      // #1E293B
    private static final Color CARD_BORDER = new Color(51, 65, 85);  // #334155
    private static final Color CYAN_NEON = new Color(56, 189, 248);  // #38BDF8
    private static final Color GREEN_NEON = new Color(16, 185, 129); // #10B981
    private static final Color AMBER_NEON = new Color(245, 158, 11); // #F59E0B
    private static final Color RED_NEON = new Color(239, 68, 68);    // #EF4444
    private static final Color PURPLE_NEON = new Color(168, 85, 247); // #A855F7
    private static final Color TEXT_LIGHT = new Color(248, 250, 252); // #F8FAFC
    private static final Color TEXT_MUTED = new Color(148, 163, 184); // #94A3B8

    private final RegistryClient registryClient;
    private CacheClient cacheClient;
    private ServerConfig config;

    // Simulated Dead Nodes Set (Fault Injection)
    private final Set<Integer> simulatedDeadNodes = ConcurrentHashMap.newKeySet();

    // Cache Explorer Key Store (Key -> KeyItem)
    private final Map<String, KeyExplorerItem> trackedKeys = new ConcurrentHashMap<>();

    // Tab 1 Components
    private JLabel totalHealthLabel;
    private JLabel totalKeysLabel;
    private JLabel totalReqsLabel;
    private JLabel registryAddrLabel;
    private JPanel nodeCardsPanel;
    private DefaultTableModel statsTableModel;
    private Timer refreshTimer;

    // Tab 2 Components (Client & Key Explorer)
    private JTextField keyField;
    private JTextField valueField;
    private JSpinner ttlSpinner;
    private JLabel primaryNodePill;
    private JLabel replicaNodePill;
    private JLabel responseStatusPill;
    private JTextArea clientLogArea;
    private DefaultTableModel keyExplorerModel;

    // Tab 3 Components (Load Test)
    private JSpinner threadsSpinner;
    private JSpinner requestsSpinner;
    private JProgressBar testProgressBar;
    private JLabel benchThroughputLabel;
    private JLabel benchAvgLatencyLabel;
    private JLabel benchSuccessRateLabel;
    private JLabel benchMinMaxLabel;
    private JTextArea loadTestLogArea;
    private JButton startTestBtn;
    private JButton exportReportBtn;
    private String lastReportSummary = "";

    public static class KeyExplorerItem {
        private final String key;
        private final String value;
        private final int primaryNode;
        private final int replicaNode;
        private final long createdAtMs;
        private final int ttlSeconds;

        public KeyExplorerItem(String key, String value, int primaryNode, int replicaNode, long createdAtMs, int ttlSeconds) {
            this.key = key;
            this.value = value;
            this.primaryNode = primaryNode;
            this.replicaNode = replicaNode;
            this.createdAtMs = createdAtMs;
            this.ttlSeconds = ttlSeconds;
        }

        public String key() { return key; }
        public String value() { return value; }
        public int primaryNode() { return primaryNode; }
        public int replicaNode() { return replicaNode; }
        public long createdAtMs() { return createdAtMs; }
        public int ttlSeconds() { return ttlSeconds; }
    }

    public KeyNetGuiApp() {
        super("KeyNet — Distributed Cache System Master Dashboard (Module 5 - Nguyễn Văn Nhật)");
        this.registryClient = new RegistryClient();
        initUI();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1080, 760);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);

        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        try {
            config = ServerConfig.load("config/config.properties");
            cacheClient = CacheClient.loadFromConfig("config/config.properties");
        } catch (IOException e) {
            System.err.println("Cảnh báo: Không thể tải file config/config.properties");
        }

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 13));
        tabbedPane.setBackground(CARD_BG);
        tabbedPane.setForeground(TEXT_LIGHT);

        tabbedPane.addTab("📊 CLUSTER DASHBOARD & FAULT INJECTION", createDashboardPanel());
        tabbedPane.addTab("⚡ CACHE CLIENT & KEY EXPLORER", createClientPanel());
        tabbedPane.addTab("🚀 LOAD TEST & EXPORT REPORT", createLoadTestPanel());

        add(tabbedPane, BorderLayout.CENTER);

        refreshTimer = new Timer(1000, e -> {
            refreshClusterMetrics();
            refreshKeyExplorerTable();
        });
        refreshTimer.start();
        refreshClusterMetrics();
    }

    // =========================================================================
    // TAB 1: CLUSTER DASHBOARD & FAULT INJECTION
    // =========================================================================
    private JPanel createDashboardPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // 4 Stat Cards Top Row
        JPanel topRow = new JPanel(new GridLayout(1, 4, 12, 12));
        topRow.setBackground(BG_DARK);

        totalHealthLabel = new JLabel("CONNECTING...", SwingConstants.CENTER);
        totalKeysLabel = new JLabel("0 Keys", SwingConstants.CENTER);
        totalReqsLabel = new JLabel("0 Ops", SwingConstants.CENTER);
        registryAddrLabel = new JLabel("127.0.0.1:6000", SwingConstants.CENTER);

        topRow.add(createMetricWidget("CLUSTER HEALTH", totalHealthLabel, GREEN_NEON));
        topRow.add(createMetricWidget("TOTAL KEYS IN RAM", totalKeysLabel, CYAN_NEON));
        topRow.add(createMetricWidget("TOTAL REQUESTS", totalReqsLabel, AMBER_NEON));
        topRow.add(createMetricWidget("REGISTRY SERVER", registryAddrLabel, TEXT_LIGHT));

        panel.add(topRow, BorderLayout.NORTH);

        // Center Panel: Node Cards & Metrics Table
        JPanel centerPanel = new JPanel(new BorderLayout(15, 15));
        centerPanel.setBackground(BG_DARK);

        // Node Cards Visualizer Grid (With 1-Click Fault Injection Buttons)
        nodeCardsPanel = new JPanel(new GridLayout(1, 3, 12, 12));
        nodeCardsPanel.setBackground(BG_DARK);
        centerPanel.add(nodeCardsPanel, BorderLayout.NORTH);

        // Metrics Table
        String[] columns = {"Server Index", "Node Address (Host:Port)", "Health Status", "Keys in RAM", "Total Requests"};
        statsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        JTable table = new JTable(statsTableModel);
        table.setBackground(CARD_BG);
        table.setForeground(TEXT_LIGHT);
        table.setGridColor(CARD_BORDER);
        table.setRowHeight(32);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 13));
        table.getTableHeader().setBackground(CARD_BORDER);
        table.getTableHeader().setForeground(TEXT_LIGHT);

        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val, boolean isSel, boolean hasFocus, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, val, isSel, hasFocus, r, c);
                setHorizontalAlignment(CENTER);
                String s = String.valueOf(val);
                if (s.startsWith("UP")) {
                    comp.setForeground(GREEN_NEON);
                    setFont(getFont().deriveFont(Font.BOLD));
                } else {
                    comp.setForeground(RED_NEON);
                    setFont(getFont().deriveFont(Font.BOLD));
                }
                return comp;
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(CARD_BG);
        scrollPane.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(CARD_BORDER), "Detailed Node Metrics Table", 0, 0, new Font("Segoe UI", Font.BOLD, 12), TEXT_LIGHT));

        centerPanel.add(scrollPane, BorderLayout.CENTER);
        panel.add(centerPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createMetricWidget(String title, JLabel valueLabel, Color accentColor) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1),
                new EmptyBorder(10, 10, 10, 10)));

        JLabel titleLbl = new JLabel(title, SwingConstants.CENTER);
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        titleLbl.setForeground(TEXT_MUTED);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        valueLabel.setForeground(accentColor);

        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    // =========================================================================
    // TAB 2: CACHE CLIENT & KEY EXPLORER (WITH LIVE TTL TRACKER)
    // =========================================================================
    private JPanel createClientPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // Form Inputs Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBackground(CARD_BG);
        formPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(CARD_BORDER), "Interactive Cache Client Operations", 0, 0, new Font("Segoe UI", Font.BOLD, 13), TEXT_LIGHT),
                new EmptyBorder(12, 12, 12, 12)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Key
        gbc.gridx = 0; gbc.gridy = 0;
        JLabel kLbl = new JLabel("Key (Khóa):");
        kLbl.setForeground(TEXT_LIGHT);
        formPanel.add(kLbl, gbc);

        keyField = new JTextField(18);
        styleTextField(keyField);
        gbc.gridx = 1; gbc.gridy = 0;
        formPanel.add(keyField, gbc);

        // Value
        gbc.gridx = 2; gbc.gridy = 0;
        JLabel vLbl = new JLabel("Value (Giá trị):");
        vLbl.setForeground(TEXT_LIGHT);
        formPanel.add(vLbl, gbc);

        valueField = new JTextField(18);
        styleTextField(valueField);
        gbc.gridx = 3; gbc.gridy = 0;
        formPanel.add(valueField, gbc);

        // TTL Spinner
        gbc.gridx = 4; gbc.gridy = 0;
        JLabel ttlLbl = new JLabel("TTL (giây):");
        ttlLbl.setForeground(TEXT_LIGHT);
        formPanel.add(ttlLbl, gbc);

        ttlSpinner = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
        gbc.gridx = 5; gbc.gridy = 0;
        formPanel.add(ttlSpinner, gbc);

        // Routing Visual Pills
        JPanel routingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        routingPanel.setBackground(CARD_BG);

        primaryNodePill = new JLabel(" PRIMARY NODE: [Nhập Key] ");
        stylePill(primaryNodePill, CYAN_NEON);

        replicaNodePill = new JLabel(" REPLICA NODE: [Nhập Key] ");
        stylePill(replicaNodePill, AMBER_NEON);

        routingPanel.add(primaryNodePill);
        routingPanel.add(replicaNodePill);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 6;
        formPanel.add(routingPanel, gbc);

        // Action Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        btnPanel.setBackground(CARD_BG);

        JButton putBtn = createStyledButton("PUT (Ghi)", GREEN_NEON);
        JButton getBtn = createStyledButton("GET (Đọc)", CYAN_NEON);
        JButton delBtn = createStyledButton("DELETE (Xóa)", RED_NEON);
        JButton infoBtn = createStyledButton("INFO (Định tuyến)", TEXT_LIGHT);

        putBtn.addActionListener(e -> executeClientAction("PUT"));
        getBtn.addActionListener(e -> executeClientAction("GET"));
        delBtn.addActionListener(e -> executeClientAction("DELETE"));
        infoBtn.addActionListener(e -> executeClientAction("INFO"));

        btnPanel.add(putBtn);
        btnPanel.add(getBtn);
        btnPanel.add(delBtn);
        btnPanel.add(infoBtn);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 6;
        formPanel.add(btnPanel, gbc);

        panel.add(formPanel, BorderLayout.NORTH);

        // Center Split: Key Explorer Table & Response Log
        JPanel centerSplit = new JPanel(new GridLayout(1, 2, 12, 12));
        centerSplit.setBackground(BG_DARK);

        // Left Panel: Cache Key Explorer Table with Live TTL Countdown
        String[] keyCols = {"Key Name", "Value Snippet", "Primary", "Replica", "TTL Countdown"};
        keyExplorerModel = new DefaultTableModel(keyCols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable keyTable = new JTable(keyExplorerModel);
        keyTable.setBackground(CARD_BG);
        keyTable.setForeground(TEXT_LIGHT);
        keyTable.setGridColor(CARD_BORDER);
        keyTable.setRowHeight(28);
        keyTable.setFont(new Font("Consolas", Font.PLAIN, 12));
        keyTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        keyTable.getTableHeader().setBackground(CARD_BORDER);
        keyTable.getTableHeader().setForeground(TEXT_LIGHT);

        keyTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean isS, boolean hasF, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, isS, hasF, r, c);
                setHorizontalAlignment(CENTER);
                comp.setForeground(PURPLE_NEON);
                setFont(getFont().deriveFont(Font.BOLD));
                return comp;
            }
        });

        JScrollPane keyScroll = new JScrollPane(keyTable);
        keyScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(CARD_BORDER), "🔍 Cache Key Explorer (Live RAM Keys & TTL)", 0, 0, new Font("Segoe UI", Font.BOLD, 12), TEXT_LIGHT));
        centerSplit.add(keyScroll);

        // Right Panel: Command Execution Log
        JPanel responsePanel = new JPanel(new BorderLayout(8, 8));
        responsePanel.setBackground(CARD_BG);
        responsePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(CARD_BORDER), "Execution Console Log", 0, 0, new Font("Segoe UI", Font.BOLD, 12), TEXT_LIGHT),
                new EmptyBorder(8, 8, 8, 8)));

        responseStatusPill = new JLabel(" RESPONSE STATUS: WAITING ");
        stylePill(responseStatusPill, TEXT_MUTED);
        responsePanel.add(responseStatusPill, BorderLayout.NORTH);

        clientLogArea = new JTextArea();
        clientLogArea.setEditable(false);
        clientLogArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        clientLogArea.setBackground(BG_DARK);
        clientLogArea.setForeground(TEXT_LIGHT);
        clientLogArea.setMargin(new Insets(8, 8, 8, 8));

        JScrollPane logScroll = new JScrollPane(clientLogArea);
        logScroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
        responsePanel.add(logScroll, BorderLayout.CENTER);

        centerSplit.add(responsePanel);

        panel.add(centerSplit, BorderLayout.CENTER);

        return panel;
    }

    // =========================================================================
    // TAB 3: LOAD TEST & EXPORT BENCHMARK REPORT
    // =========================================================================
    private JPanel createLoadTestPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // Controls Bar
        JPanel ctrlCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        ctrlCard.setBackground(CARD_BG);
        ctrlCard.setBorder(BorderFactory.createLineBorder(CARD_BORDER));

        JLabel tLbl = new JLabel("Số luồng (Threads):");
        tLbl.setForeground(TEXT_LIGHT);
        threadsSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 200, 5));

        JLabel rLbl = new JLabel("Tổng Requests:");
        rLbl.setForeground(TEXT_LIGHT);
        requestsSpinner = new JSpinner(new SpinnerNumberModel(2000, 100, 50000, 500));

        startTestBtn = createStyledButton("▶ BẮT ĐẦU LOAD TEST", GREEN_NEON);
        startTestBtn.addActionListener(e -> runAsyncLoadTest());

        exportReportBtn = createStyledButton("📥 XUẤT BÁO CÁO (EXPORT)", PURPLE_NEON);
        exportReportBtn.addActionListener(e -> exportBenchmarkReport());

        ctrlCard.add(tLbl);
        ctrlCard.add(threadsSpinner);
        ctrlCard.add(rLbl);
        ctrlCard.add(requestsSpinner);
        ctrlCard.add(startTestBtn);
        ctrlCard.add(exportReportBtn);

        panel.add(ctrlCard, BorderLayout.NORTH);

        // Metrics Grid & Progress
        JPanel metricsPanel = new JPanel(new BorderLayout(12, 12));
        metricsPanel.setBackground(BG_DARK);

        testProgressBar = new JProgressBar(0, 100);
        testProgressBar.setStringPainted(true);
        testProgressBar.setBackground(CARD_BG);
        testProgressBar.setForeground(CYAN_NEON);
        metricsPanel.add(testProgressBar, BorderLayout.NORTH);

        JPanel cardsRow = new JPanel(new GridLayout(1, 4, 12, 12));
        cardsRow.setBackground(BG_DARK);

        benchThroughputLabel = new JLabel("0 ops/sec", SwingConstants.CENTER);
        benchAvgLatencyLabel = new JLabel("0.00 ms", SwingConstants.CENTER);
        benchSuccessRateLabel = new JLabel("0.00 %", SwingConstants.CENTER);
        benchMinMaxLabel = new JLabel("0 / 0 ms", SwingConstants.CENTER);

        cardsRow.add(createMetricWidget("THROUGHPUT", benchThroughputLabel, CYAN_NEON));
        cardsRow.add(createMetricWidget("AVG LATENCY", benchAvgLatencyLabel, AMBER_NEON));
        cardsRow.add(createMetricWidget("SUCCESS RATE", benchSuccessRateLabel, GREEN_NEON));
        cardsRow.add(createMetricWidget("MIN / MAX LATENCY", benchMinMaxLabel, TEXT_LIGHT));

        metricsPanel.add(cardsRow, BorderLayout.CENTER);
        panel.add(metricsPanel, BorderLayout.CENTER);

        // Log Console
        loadTestLogArea = new JTextArea();
        loadTestLogArea.setEditable(false);
        loadTestLogArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        loadTestLogArea.setBackground(CARD_BG);
        loadTestLogArea.setForeground(TEXT_LIGHT);
        loadTestLogArea.setMargin(new Insets(10, 10, 10, 10));

        JScrollPane scroll = new JScrollPane(loadTestLogArea);
        scroll.setPreferredSize(new Dimension(800, 220));
        scroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(CARD_BORDER), "Load Test Execution Output", 0, 0, new Font("Segoe UI", Font.BOLD, 12), TEXT_LIGHT));
        panel.add(scroll, BorderLayout.SOUTH);

        return panel;
    }

    // =========================================================================
    // LOGIC & DATA UPDATES
    // =========================================================================
    private void refreshClusterMetrics() {
        String rawStats = registryClient.getRawStats();
        statsTableModel.setRowCount(0);
        nodeCardsPanel.removeAll();

        int totalNodes = config != null ? config.serverCount() : 3;

        if (rawStats == null) {
            totalHealthLabel.setText("UNREACHABLE");
            totalHealthLabel.setForeground(RED_NEON);
            totalKeysLabel.setText("0 Keys");
            totalReqsLabel.setText("0 Ops");

            for (int i = 0; i < totalNodes; i++) {
                String hostPort = "127.0.0.1:500" + (i + 1);
                String st = simulatedDeadNodes.contains(i) ? "DOWN (SIMULATED)" : "DOWN";
                statsTableModel.addRow(new Object[]{"Server #" + i, hostPort, st, 0, 0});
                nodeCardsPanel.add(createNodeCard(i, hostPort, st, 0, 0));
            }
            nodeCardsPanel.revalidate();
            nodeCardsPanel.repaint();
            return;
        }

        List<RegistryClient.ServerStatRecord> stats = registryClient.getParsedStats();
        Map<Integer, RegistryClient.ServerStatRecord> statMap = new HashMap<>();
        long grandTotalKeys = 0;
        long grandTotalReqs = 0;

        for (RegistryClient.ServerStatRecord s : stats) {
            statMap.put(s.serverIndex(), s);
            grandTotalKeys += s.keyCount();
            grandTotalReqs += s.requestCount();
        }

        int upCount = 0;
        for (int i = 0; i < totalNodes; i++) {
            RegistryClient.ServerStatRecord s = statMap.get(i);
            String hostPort = "127.0.0.1:500" + (i + 1);
            if (config != null && i < config.servers().size()) {
                ServerConfig.ServerAddress addr = config.servers().get(i);
                hostPort = addr.host() + ":" + addr.port();
            }

            boolean isSimulatedDead = simulatedDeadNodes.contains(i);
            String st = isSimulatedDead ? "DOWN (SIMULATED)" : (s != null && s.status() == ServerNode.Status.UP ? "UP" : "DOWN");
            int kc = isSimulatedDead ? 0 : (s != null ? s.keyCount() : 0);
            long rc = isSimulatedDead ? 0 : (s != null ? s.requestCount() : 0);

            if ("UP".equalsIgnoreCase(st)) {
                upCount++;
            }

            statsTableModel.addRow(new Object[]{"Server #" + i, hostPort, st, kc, rc});
            nodeCardsPanel.add(createNodeCard(i, hostPort, st, kc, rc));
        }

        totalKeysLabel.setText(String.format("%,d Keys", grandTotalKeys));
        totalReqsLabel.setText(String.format("%,d Ops", grandTotalReqs));

        if (upCount == totalNodes) {
            totalHealthLabel.setText("HEALTHY (" + upCount + "/" + totalNodes + " UP)");
            totalHealthLabel.setForeground(GREEN_NEON);
        } else if (upCount > 0) {
            totalHealthLabel.setText("DEGRADED (" + upCount + "/" + totalNodes + " UP)");
            totalHealthLabel.setForeground(AMBER_NEON);
        } else {
            totalHealthLabel.setText("OFFLINE (0/" + totalNodes + " UP)");
            totalHealthLabel.setForeground(RED_NEON);
        }

        nodeCardsPanel.revalidate();
        nodeCardsPanel.repaint();
    }

    private JPanel createNodeCard(int index, String hostPort, String status, int keys, long reqs) {
        JPanel card = new JPanel(new BorderLayout(8, 8));
        card.setBackground(CARD_BG);

        boolean isUp = status.startsWith("UP");
        Color borderColor = isUp ? GREEN_NEON : RED_NEON;
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 2),
                new EmptyBorder(10, 10, 10, 10)));

        // Header info
        JPanel infoPanel = new JPanel(new GridLayout(4, 1, 2, 2));
        infoPanel.setBackground(CARD_BG);

        JLabel nameLbl = new JLabel("SERVER #" + index, SwingConstants.LEFT);
        nameLbl.setFont(new Font("Segoe UI", Font.BOLD, 14));
        nameLbl.setForeground(TEXT_LIGHT);

        JLabel addrLbl = new JLabel("📍 " + hostPort);
        addrLbl.setForeground(TEXT_MUTED);

        JLabel stLbl = new JLabel("STATUS: " + status);
        stLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        stLbl.setForeground(borderColor);

        JLabel metricsLbl = new JLabel("🔑 " + keys + " Keys | ⚡ " + reqs + " Ops");
        metricsLbl.setForeground(TEXT_LIGHT);

        infoPanel.add(nameLbl);
        infoPanel.add(addrLbl);
        infoPanel.add(stLbl);
        infoPanel.add(metricsLbl);

        card.add(infoPanel, BorderLayout.CENTER);

        // 1-Click Fault Injection Control Buttons
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        actionPanel.setBackground(CARD_BG);

        JButton killBtn = new JButton("💥 Sập Node");
        killBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        killBtn.setBackground(CARD_BG);
        killBtn.setForeground(RED_NEON);
        killBtn.setFocusPainted(false);
        killBtn.addActionListener(e -> {
            simulatedDeadNodes.add(index);
            refreshClusterMetrics();
            appendClientLog("[FAULT INJECTION] 💥 Giả lập sập Server #" + index);
        });

        JButton recoverBtn = new JButton("🔄 Phục hồi");
        recoverBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        recoverBtn.setBackground(CARD_BG);
        recoverBtn.setForeground(GREEN_NEON);
        recoverBtn.setFocusPainted(false);
        recoverBtn.addActionListener(e -> {
            simulatedDeadNodes.remove(index);
            refreshClusterMetrics();
            appendClientLog("[RECOVERY] 🔄 Khôi phục hoạt động Server #" + index);
        });

        actionPanel.add(killBtn);
        actionPanel.add(recoverBtn);

        card.add(actionPanel, BorderLayout.SOUTH);
        return card;
    }

    private void refreshKeyExplorerTable() {
        keyExplorerModel.setRowCount(0);
        long now = System.currentTimeMillis();

        for (KeyExplorerItem item : trackedKeys.values()) {
            long elapsedSec = (now - item.createdAtMs()) / 1000;
            long remaining = item.ttlSeconds() - elapsedSec;

            if (remaining <= 0) {
                trackedKeys.remove(item.key());
                continue;
            }

            String valSnippet = item.value().length() > 15 ? item.value().substring(0, 15) + "..." : item.value();
            keyExplorerModel.addRow(new Object[]{
                    item.key(),
                    valSnippet,
                    "Server #" + item.primaryNode(),
                    "Server #" + item.replicaNode(),
                    remaining + "s TTL"
            });
        }
    }

    private void executeClientAction(String action) {
        String key = keyField.getText().trim();
        String val = valueField.getText().trim();
        int ttl = (Integer) ttlSpinner.getValue();

        if (key.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Key!", "Lỗi Input", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int totalNodes = config != null ? config.serverCount() : 3;
        int primaryIdx = HashUtil.computeServerIndex(key, totalNodes);
        int replicaIdx = HashUtil.replicaIndex(primaryIdx, totalNodes);

        primaryNodePill.setText(" PRIMARY NODE: Server #" + primaryIdx + " ");
        replicaNodePill.setText(" REPLICA NODE: Server #" + replicaIdx + " ");

        if ("INFO".equals(action)) {
            appendClientLog("[INFO] Key '" + key + "' -> Primary: Server #" + primaryIdx + ", Replica: Server #" + replicaIdx);
            stylePill(responseStatusPill, CYAN_NEON);
            responseStatusPill.setText(" RESPONSE STATUS: ROUTER INFO ");
            return;
        }

        if (cacheClient == null) {
            appendClientLog("[ERROR] CacheClient chưa được khởi tạo!");
            return;
        }

        new Thread(() -> {
            try {
                switch (action) {
                    case "PUT" -> {
                        if (val.isEmpty()) {
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Lệnh PUT yêu cầu nhập Value!", "Lỗi", JOptionPane.ERROR_MESSAGE));
                            return;
                        }
                        String res = cacheClient.put(key, val);
                        SwingUtilities.invokeLater(() -> {
                            appendClientLog("[PUT] key='" + key + "', val='" + val + "' ➔ " + res);
                            stylePill(responseStatusPill, res.startsWith("OK") ? GREEN_NEON : RED_NEON);
                            responseStatusPill.setText(" RESPONSE STATUS: " + res + " ");
                            if (res.startsWith("OK")) {
                                trackedKeys.put(key, new KeyExplorerItem(key, val, primaryIdx, replicaIdx, System.currentTimeMillis(), ttl));
                                refreshKeyExplorerTable();
                            }
                        });
                    }
                    case "GET" -> {
                        String res = cacheClient.get(key);
                        SwingUtilities.invokeLater(() -> {
                            appendClientLog("[GET] key='" + key + "' ➔ " + res);
                            stylePill(responseStatusPill, res.startsWith("VALUE") ? GREEN_NEON : AMBER_NEON);
                            responseStatusPill.setText(" RESPONSE STATUS: " + res + " ");
                        });
                    }
                    case "DELETE" -> {
                        String res = cacheClient.delete(key);
                        SwingUtilities.invokeLater(() -> {
                            appendClientLog("[DELETE] key='" + key + "' ➔ " + res);
                            stylePill(responseStatusPill, res.startsWith("OK") ? GREEN_NEON : RED_NEON);
                            responseStatusPill.setText(" RESPONSE STATUS: " + res + " ");
                            if (res.startsWith("OK")) {
                                trackedKeys.remove(key);
                                refreshKeyExplorerTable();
                            }
                        });
                    }
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> appendClientLog("[EXCEPTION] " + ex.getMessage()));
            }
        }).start();
    }

    private void runAsyncLoadTest() {
        if (cacheClient == null) {
            JOptionPane.showMessageDialog(this, "CacheClient chưa sẵn sàng!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int threads = (Integer) threadsSpinner.getValue();
        int totalRequests = (Integer) requestsSpinner.getValue();

        startTestBtn.setEnabled(false);
        testProgressBar.setValue(0);
        loadTestLogArea.setText("");
        appendLoadTestLog("▶ BẮT ĐẦU LOAD TEST ĐA LUỒNG...");
        appendLoadTestLog("Số luồng: " + threads + " | Tổng requests: " + totalRequests + "\n");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                AtomicLong success = new AtomicLong(0);
                AtomicLong errors = new AtomicLong(0);
                AtomicLong totalLatency = new AtomicLong(0);
                AtomicLong minLat = new AtomicLong(Long.MAX_VALUE);
                AtomicLong maxLat = new AtomicLong(0);

                long startTime = System.currentTimeMillis();

                for (int i = 0; i < totalRequests; i++) {
                    final int reqId = i;
                    pool.submit(() -> {
                        long start = System.currentTimeMillis();
                        boolean isPut = (reqId % 10) < 3;
                        String k = "gui_test_" + (reqId % 100);
                        String v = "val_" + reqId;

                        try {
                            if (isPut) {
                                String r = cacheClient.put(k, v);
                                if ("OK".equalsIgnoreCase(r)) success.incrementAndGet();
                                else errors.incrementAndGet();
                            } else {
                                cacheClient.get(k);
                                success.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            long lat = System.currentTimeMillis() - start;
                            totalLatency.addAndGet(lat);
                            minLat.accumulateAndGet(lat, Math::min);
                            maxLat.accumulateAndGet(lat, Math::max);

                            long done = success.get() + errors.get();
                            int pct = (int) (done * 100 / totalRequests);
                            SwingUtilities.invokeLater(() -> testProgressBar.setValue(pct));
                        }
                    });
                }

                pool.shutdown();
                pool.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES);

                long totalTimeMs = System.currentTimeMillis() - startTime;
                double sec = totalTimeMs / 1000.0;
                long succ = success.get();
                long err = errors.get();
                double tp = sec > 0 ? (succ + err) / sec : 0;
                double avgLat = (succ + err) > 0 ? (double) totalLatency.get() / (succ + err) : 0;
                double succRate = (succ * 100.0) / totalRequests;

                SwingUtilities.invokeLater(() -> {
                    benchThroughputLabel.setText(String.format("%.1f ops/sec", tp));
                    benchAvgLatencyLabel.setText(String.format("%.2f ms", avgLat));
                    benchSuccessRateLabel.setText(String.format("%.2f %%", succRate));
                    benchMinMaxLabel.setText(String.format("%d / %d ms", minLat.get() == Long.MAX_VALUE ? 0 : minLat.get(), maxLat.get()));

                    StringBuilder sb = new StringBuilder();
                    sb.append("=================================================\n");
                    sb.append("       BÁO CÁO BENCHMARK DỮ LIỆU CỤM PHÂN TÁN     \n");
                    sb.append("=================================================\n");
                    sb.append("Thời gian thực hiện : ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                    sb.append("Số luồng đồng thời  : ").append(threads).append(" threads\n");
                    sb.append("Tổng số Requests    : ").append(totalRequests).append("\n");
                    sb.append("Chịu tải (Throughput): ").append(String.format("%.2f ops/sec", tp)).append("\n");
                    sb.append("Độ trễ trung bình   : ").append(String.format("%.2f ms", avgLat)).append("\n");
                    sb.append("Tỷ lệ thành công    : ").append(String.format("%.2f %%", succRate)).append("\n");
                    sb.append("=================================================\n");

                    lastReportSummary = sb.toString();
                    appendLoadTestLog(lastReportSummary);
                    appendLoadTestLog("✔ HOÀN THÀNH LOAD TEST! Bạn có thể bấm [📥 XUẤT BÁO CÁO] để lưu file.");

                    startTestBtn.setEnabled(true);
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLoadTestLog("❌ LỖI LOAD TEST: " + ex.getMessage());
                    startTestBtn.setEnabled(true);
                });
            }
        });
    }

    private void exportBenchmarkReport() {
        if (lastReportSummary.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Chưa có kết quả benchmark. Vui lòng bấm [▶ BẮT ĐẦU LOAD TEST] trước!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String filePath = "docs/load_test_report.txt";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, StandardCharsets.UTF_8, true))) {
            writer.println(lastReportSummary);
            JOptionPane.showMessageDialog(this,
                    "✅ Đã xuất báo cáo thành công ra file:\n" + filePath,
                    "Xuất Báo Cáo Thành Công", JOptionPane.INFORMATION_MESSAGE);
            appendLoadTestLog("✅ Đã xuất file báo cáo tại: " + filePath);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không thể lưu file báo cáo: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void styleTextField(JTextField tf) {
        tf.setFont(new Font("Consolas", Font.PLAIN, 13));
        tf.setBackground(BG_DARK);
        tf.setForeground(TEXT_LIGHT);
        tf.setCaretColor(CYAN_NEON);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1),
                new EmptyBorder(5, 8, 5, 8)));
    }

    private void stylePill(JLabel label, Color borderBg) {
        label.setFont(new Font("Segoe UI", Font.BOLD, 12));
        label.setForeground(borderBg);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderBg, 1),
                new EmptyBorder(4, 10, 4, 10)));
    }

    private JButton createStyledButton(String text, Color accentColor) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBackground(CARD_BG);
        btn.setForeground(accentColor);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accentColor, 1),
                new EmptyBorder(6, 14, 6, 14)));
        return btn;
    }

    private void appendClientLog(String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        clientLogArea.append("[" + ts + "] " + msg + "\n");
        clientLogArea.setCaretPosition(clientLogArea.getDocument().getLength());
    }

    private void appendLoadTestLog(String msg) {
        loadTestLogArea.append(msg + "\n");
        loadTestLogArea.setCaretPosition(loadTestLogArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            KeyNetGuiApp app = new KeyNetGuiApp();
            app.setVisible(true);
        });
    }
}
