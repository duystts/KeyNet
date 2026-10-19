package com.nhom05.cache.gui;

import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;

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

    // Theo doi trang thai truoc do de ghi log su kien
    private final Map<Integer, String> previousNodeStatuses = new HashMap<>();
    private Boolean previousRegistryConnected = null;

    public DashboardPanel(ServerConfig config, RegistryClient registryClient) {
        this.config = config;
        this.registryClient = registryClient;

        setLayout(new BorderLayout(12, 12));
        setBackground(BG_MAIN);
        setBorder(new EmptyBorder(14, 14, 14, 14));

        initUI();

        // Timer dinh ky 2 giay, chay tren EDT de goi SwingWorker
        this.refreshTimer = new Timer(DEFAULT_REFRESH_INTERVAL_MS, e -> refresh());
        this.refreshTimer.start();

        // Nap du lieu lan dau
        refresh();
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
        return panel;
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
                return DashboardDataModel.fetch(config, registryClient);
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

        // 3. Cap nhat Bang Server
        tableModel.setNodes(model.nodes());

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
            this.nodes = (newNodes != null) ? List.copyOf(newNodes) : List.of();
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
                label.setText("⚠ " + status);
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
