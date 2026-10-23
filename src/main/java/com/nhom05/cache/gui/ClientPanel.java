package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Module 5 - Panel GUI thao tac voi he thong Cache (PUT / GET / DELETE / INFO)
 * va xem danh sach Key Explorer.
 *
 * Tuan thu chat che dac ta:
 * - Dung CacheClient.send() de thuc hien cac thao tac mang (chay tren SwingWorker).
 * - Hien thi ro rang Response.line(), server da phuc vu (servedBy) va co failover hay khong (failedOver).
 * - Nut INFO tinh toan routing bang router().primaryIndex(key) / replicaIndex(...) ma KHONG gui qua mang.
 * - Khong lam o nhap TTL (server dung TTL chung trong cau hinh).
 * - Co Key Explorer quan ly danh sach key da ghi va Nhat ky lenh.
 */
public class ClientPanel extends JPanel {

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

    private final CacheClient cacheClient;
    private final ClientHistoryModel historyModel;
    private final AtomicBoolean isExecuting = new AtomicBoolean(false);

    // Form Components
    private JTextField txtKey;
    private JTextField txtValue;
    private JButton btnPut;
    private JButton btnGet;
    private JButton btnDelete;
    private JButton btnInfo;
    private JButton btnClear;

    // Hash & Routing Display
    private JLabel lblHashRouteInfo;
    private JLabel lblLastResponseStatus;
    private JLabel lblServedBy;
    private JLabel lblFailoverBadge;
    private JLabel lblFeedbackStatus;

    // Tables
    private KeyExplorerTableModel keyTableModel;
    private JTable keyTable;
    private CommandLogTableModel logTableModel;
    private JTable logTable;

    public ClientPanel(CacheClient cacheClient) {
        this(cacheClient, new ClientHistoryModel());
    }

    public ClientPanel(CacheClient cacheClient, ClientHistoryModel historyModel) {
        this.cacheClient = cacheClient;
        this.historyModel = historyModel != null ? historyModel : new ClientHistoryModel();

        setLayout(new BorderLayout(12, 12));
        setBackground(BG_MAIN);
        setBorder(new EmptyBorder(14, 14, 14, 14));

        initUI();

        // Lang nghe thay doi tu model de cap nhat lai bang
        this.historyModel.addChangeListener(() -> SwingUtilities.invokeLater(this::refreshTables));
    }

    private void initUI() {
        // 1. Header
        add(createHeaderPanel(), BorderLayout.NORTH);

        // 2. Center: Input Form + Live Routing + Tabs (Key Explorer / Log)
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setOpaque(false);

        // Nua tren: Form thao tac va The hien thi dinh tuyen Hash
        JPanel topSection = new JPanel(new BorderLayout(10, 10));
        topSection.setOpaque(false);
        topSection.add(createInputFormSection(), BorderLayout.NORTH);
        topSection.add(createResponseStatusSection(), BorderLayout.CENTER);

        centerPanel.add(topSection, BorderLayout.NORTH);

        // Nua duoi: TabbedPane chia Key Explorer va Nhat ky thao tac
        centerPanel.add(createDataTabsSection(), BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(10, 6));
        header.setOpaque(false);

        JPanel titleBox = new JPanel(new GridLayout(2, 1, 0, 2));
        titleBox.setOpaque(false);

        JLabel titleLabel = new JLabel("KeyNet — Cache Client & Key Explorer");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        titleLabel.setForeground(new Color(33, 33, 33));

        JLabel subLabel = new JLabel("Thực hiện các thao tác PUT / GET / DELETE theo mã băm và theo dõi chuyển vùng dự phòng (Failover)");
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subLabel.setForeground(new Color(66, 66, 66));

        titleBox.add(titleLabel);
        titleBox.add(subLabel);
        header.add(titleBox, BorderLayout.WEST);

        lblFeedbackStatus = new JLabel("Sẵn sàng thao tác", JLabel.RIGHT);
        lblFeedbackStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblFeedbackStatus.setForeground(new Color(46, 125, 50));
        header.add(lblFeedbackStatus, BorderLayout.EAST);

        return header;
    }

    private JPanel createInputFormSection() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(BG_CARD);

        TitledBorder border = BorderFactory.createTitledBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                " Thao Tác Với Cache Server ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(33, 33, 33)
        );
        panel.setBorder(new CompoundBorder(border, new EmptyBorder(10, 14, 12, 14)));

        // Form Fields (Key & Value)
        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        fieldsPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 1: Key
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        JLabel lblKey = new JLabel("Key (Khóa):");
        lblKey.setFont(new Font("Segoe UI", Font.BOLD, 12));
        fieldsPanel.add(lblKey, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        txtKey = new JTextField();
        txtKey.setFont(new Font("Consolas", Font.PLAIN, 13));
        txtKey.setPreferredSize(new Dimension(280, 28));
        txtKey.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateLiveHashRouting(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateLiveHashRouting(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateLiveHashRouting(); }
        });
        fieldsPanel.add(txtKey, gbc);

        // Row 2: Value
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        JLabel lblValue = new JLabel("Value (Giá trị):");
        lblValue.setFont(new Font("Segoe UI", Font.BOLD, 12));
        fieldsPanel.add(lblValue, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        txtValue = new JTextField();
        txtValue.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        txtValue.setPreferredSize(new Dimension(280, 28));
        fieldsPanel.add(txtValue, gbc);

        panel.add(fieldsPanel, BorderLayout.CENTER);

        // Buttons Panel
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        btnPanel.setOpaque(false);

        btnPut = createActionButton("PUT (Ghi)", new Color(46, 125, 50), e -> handlePut());
        btnGet = createActionButton("GET (Đọc)", new Color(21, 101, 192), e -> handleGet());
        btnDelete = createActionButton("DELETE (Xóa)", new Color(198, 40, 40), e -> handleDelete());
        btnInfo = createActionButton("INFO (Kiểm tra Hash)", new Color(106, 27, 154), e -> handleInfo());
        btnClear = createActionButton("Xóa Form", new Color(97, 97, 97), e -> handleClearForm());

        btnPanel.add(btnPut);
        btnPanel.add(btnGet);
        btnPanel.add(btnDelete);
        btnPanel.add(btnInfo);
        btnPanel.add(btnClear);

        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JButton createActionButton(String text, Color bg, java.awt.event.ActionListener al) {
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

    private JPanel createResponseStatusSection() {
        JPanel card = new JPanel(new BorderLayout(8, 6));
        card.setBackground(BG_CARD);
        card.setBorder(new CompoundBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                new EmptyBorder(8, 12, 8, 12)
        ));

        // Hang 1: Dinh tuyen Hashing truc tiep
        lblHashRouteInfo = new JLabel("Nhập Key để xem định tuyến Server chính và Server dự phòng...");
        lblHashRouteInfo.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblHashRouteInfo.setForeground(new Color(21, 101, 192));
        card.add(lblHashRouteInfo, BorderLayout.NORTH);

        // Hang 2: Ket qua phan hoi gan nhat & Failover Badge
        JPanel resultRow = new JPanel(new BorderLayout(8, 0));
        resultRow.setOpaque(false);

        lblLastResponseStatus = new JLabel("Chưa có lệnh nào được thực hiện.");
        lblLastResponseStatus.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblLastResponseStatus.setForeground(new Color(33, 33, 33));
        resultRow.add(lblLastResponseStatus, BorderLayout.WEST);

        JPanel rightDetails = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightDetails.setOpaque(false);

        lblServedBy = new JLabel("");
        lblServedBy.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        rightDetails.add(lblServedBy);

        lblFailoverBadge = new JLabel("");
        lblFailoverBadge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblFailoverBadge.setOpaque(true);
        lblFailoverBadge.setVisible(false);
        rightDetails.add(lblFailoverBadge);

        resultRow.add(rightDetails, BorderLayout.EAST);
        card.add(resultRow, BorderLayout.CENTER);

        return card;
    }

    private JTabbedPane createDataTabsSection() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 12));

        // Tab 1: Key Explorer
        tabbedPane.addTab("Key Explorer (Các Key Đã Ghi)", createKeyExplorerPanel());

        // Tab 2: Nhat ky lenh
        tabbedPane.addTab("Nhật Ký Lệnh (Command History)", createCommandLogPanel());

        return tabbedPane;
    }

    private JPanel createKeyExplorerPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(BG_CARD);
        panel.setBorder(new EmptyBorder(6, 6, 6, 6));

        keyTableModel = new KeyExplorerTableModel();
        keyTable = new JTable(keyTableModel);
        keyTable.setRowHeight(28);
        keyTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        keyTable.setSelectionBackground(new Color(227, 242, 253));
        keyTable.setSelectionForeground(Color.BLACK);
        keyTable.setBackground(Color.WHITE);
        keyTable.setShowVerticalLines(true);
        keyTable.setGridColor(new Color(238, 238, 238));

        keyTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        keyTable.getTableHeader().setBackground(new Color(245, 245, 245));
        keyTable.getTableHeader().setReorderingAllowed(false);

        // Click row -> dien Key & Value vao form
        keyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = keyTable.getSelectedRow();
                if (row >= 0 && row < keyTableModel.getRowCount()) {
                    KeyEntry entry = keyTableModel.getEntry(row);
                    if (entry != null) {
                        txtKey.setText(entry.key());
                        txtValue.setText(entry.value());
                    }
                }
            }
        });

        // Set column widths
        keyTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        keyTable.getColumnModel().getColumn(1).setPreferredWidth(250);
        keyTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        keyTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        keyTable.getColumnModel().getColumn(4).setPreferredWidth(110);

        JScrollPane scrollPane = new JScrollPane(keyTable);
        scrollPane.setBackground(Color.WHITE);
        scrollPane.getViewport().setBackground(Color.WHITE);
        scrollPane.setBorder(new LineBorder(COLOR_BORDER, 1));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Toolbar o duoi: Ho tro thao tac nhanh
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bottomBar.setOpaque(false);
        JLabel hintLabel = new JLabel("Ghi chú: Nhấp chuột vào một dòng để tự động điền Key/Value lên form thao tác.");
        hintLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        hintLabel.setForeground(new Color(66, 66, 66));
        bottomBar.add(hintLabel);

        panel.add(bottomBar, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCommandLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(BG_CARD);
        panel.setBorder(new EmptyBorder(6, 6, 6, 6));

        logTableModel = new CommandLogTableModel();
        logTable = new JTable(logTableModel);
        logTable.setRowHeight(28);
        logTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        logTable.setBackground(Color.WHITE);
        logTable.setShowVerticalLines(true);
        logTable.setGridColor(new Color(238, 238, 238));

        logTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        logTable.getTableHeader().setBackground(new Color(245, 245, 245));
        logTable.getTableHeader().setReorderingAllowed(false);

        // Renderer cho cot Failover
        logTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setHorizontalAlignment(JLabel.CENTER);
                String val = (value != null) ? value.toString() : "";
                if (val.contains("CÓ")) {
                    label.setForeground(COLOR_WARN_TEXT);
                    label.setFont(new Font("Segoe UI", Font.BOLD, 12));
                } else {
                    label.setForeground(COLOR_NEUTRAL_TEXT);
                    label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                }
                return label;
            }
        });

        // Set column widths
        logTable.getColumnModel().getColumn(0).setPreferredWidth(85);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(75);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(160);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(110);
        logTable.getColumnModel().getColumn(5).setPreferredWidth(130);
        logTable.getColumnModel().getColumn(6).setPreferredWidth(200);

        JScrollPane scrollPane = new JScrollPane(logTable);
        scrollPane.setBackground(Color.WHITE);
        scrollPane.getViewport().setBackground(Color.WHITE);
        scrollPane.setBorder(new LineBorder(COLOR_BORDER, 1));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Toolbar: Nut xoa lich su
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        toolBar.setOpaque(false);
        JButton btnClearLog = new JButton("Xóa nhật ký lệnh");
        btnClearLog.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnClearLog.setFocusPainted(false);
        btnClearLog.addActionListener(e -> historyModel.clearHistory());
        toolBar.add(btnClearLog);
        panel.add(toolBar, BorderLayout.NORTH);

        return panel;
    }

    private void refreshTables() {
        keyTableModel.setEntries(historyModel.allKeys());
        logTableModel.setLogs(historyModel.allCommandLogs());
    }

    // =========================================================================
    // Xu Ly Su Kien (Event Handlers)
    // =========================================================================

    private void updateLiveHashRouting() {
        String key = txtKey.getText().strip();
        if (key.isEmpty() || cacheClient == null || cacheClient.router() == null) {
            lblHashRouteInfo.setText("Nhập Key để xem định tuyến Server chính và Server dự phòng...");
            lblHashRouteInfo.setForeground(new Color(21, 101, 192));
            return;
        }

        String kErr = ProtocolParser.keyError(key);
        if (kErr != null) {
            lblHashRouteInfo.setText(kErr);
            lblHashRouteInfo.setForeground(COLOR_DANGER_TEXT);
            return;
        }
        lblHashRouteInfo.setForeground(new Color(21, 101, 192));

        int n = cacheClient.router().serverCount();
        int primary = cacheClient.router().primaryIndex(key);
        int replica = cacheClient.router().replicaIndex(primary);
        ServerConfig.ServerAddress primaryAddr = cacheClient.router().server(primary);
        ServerConfig.ServerAddress replicaAddr = cacheClient.router().server(replica);

        String text = String.format(
                "Định tuyến: Key \"%s\"  ->  Server chính: #%d (%s:%d)  |  Server dự phòng: #%d (%s:%d)  [|hash| mod %d = %d]",
                key, primary, primaryAddr.host(), primaryAddr.port(),
                replica, replicaAddr.host(), replicaAddr.port(),
                n, primary
        );
        lblHashRouteInfo.setText(text);
    }

    private void handleInfo() {
        String key = txtKey.getText().strip();
        String kErr = ProtocolParser.keyError(key);
        if (kErr != null) {
            displayResponseResult(kErr, -1, false, false);
            lblFeedbackStatus.setText("Lỗi: " + kErr);
            JOptionPane.showMessageDialog(this, kErr, "Lỗi cú pháp Key", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (cacheClient == null || cacheClient.router() == null) {
            JOptionPane.showMessageDialog(this, "Chưa khởi tạo CacheClient!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int primary = cacheClient.router().primaryIndex(key);
        int replica = cacheClient.router().replicaIndex(primary);
        ServerConfig.ServerAddress pAddr = cacheClient.router().server(primary);
        ServerConfig.ServerAddress rAddr = cacheClient.router().server(replica);

        String infoResult = String.format("Chính: Server #%d (%s:%d) | Dự phòng: Server #%d (%s:%d)",
                primary, pAddr.host(), pAddr.port(), replica, rAddr.host(), rAddr.port());

        displayResponseResult("INFO: " + infoResult, primary, false, true);
        historyModel.logCommand("INFO", key, "—", primary, false, infoResult);
    }

    private void handlePut() {
        String key = txtKey.getText().strip();
        String value = txtValue.getText();

        String kErr = ProtocolParser.keyError(key);
        if (kErr != null) {
            displayResponseResult(kErr, -1, false, false);
            lblFeedbackStatus.setText("Lỗi: " + kErr);
            historyModel.logCommand("PUT", key, value, -1, false, kErr);
            JOptionPane.showMessageDialog(this, kErr, "Lỗi cú pháp Key", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String vErr = ProtocolParser.valueError(value);
        if (vErr != null) {
            displayResponseResult(vErr, -1, false, false);
            lblFeedbackStatus.setText("Lỗi: " + vErr);
            historyModel.logCommand("PUT", key, value, -1, false, vErr);
            JOptionPane.showMessageDialog(this, vErr, "Lỗi cú pháp Value", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String requestLine = ProtocolParser.put(key, value);
        executeAsync("PUT", key, value, requestLine, resp -> {
            if (resp != null && resp.line().startsWith("OK")) {
                int primary = cacheClient.router().primaryIndex(key);
                int replica = cacheClient.router().replicaIndex(primary);
                historyModel.putKey(key, value, primary, replica);
            }
        });
    }

    private void handleGet() {
        String key = txtKey.getText().strip();
        String kErr = ProtocolParser.keyError(key);
        if (kErr != null) {
            displayResponseResult(kErr, -1, false, false);
            lblFeedbackStatus.setText("Lỗi: " + kErr);
            lblFeedbackStatus.setForeground(COLOR_DANGER_TEXT);
            historyModel.logCommand("GET", key, "—", -1, false, kErr);
            JOptionPane.showMessageDialog(this, kErr, "Lỗi cú pháp Key", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String requestLine = ProtocolParser.get(key);
        executeAsync("GET", key, "—", requestLine, resp -> {
            if (resp != null) {
                if (resp.line().startsWith("VALUE|")) {
                    String val = resp.line().substring("VALUE|".length());
                    txtValue.setText(val);
                    historyModel.updateValueIfPresent(key, val);
                } else if ("NOTFOUND".equalsIgnoreCase(resp.line())) {
                    txtValue.setText("");
                    historyModel.removeKey(key);
                }
            }
        });
    }

    private void handleDelete() {
        String key = txtKey.getText().strip();
        String kErr = ProtocolParser.keyError(key);
        if (kErr != null) {
            displayResponseResult(kErr, -1, false, false);
            lblFeedbackStatus.setText("Lỗi: " + kErr);
            lblFeedbackStatus.setForeground(COLOR_DANGER_TEXT);
            historyModel.logCommand("DELETE", key, "—", -1, false, kErr);
            JOptionPane.showMessageDialog(this, kErr, "Lỗi cú pháp Key", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String requestLine = ProtocolParser.delete(key);
        executeAsync("DELETE", key, "—", requestLine, resp -> {
            if (resp != null) {
                if (resp.line().startsWith("OK") || "NOTFOUND".equalsIgnoreCase(resp.line())) {
                    historyModel.removeKey(key);
                    txtValue.setText("");
                }
            }
        });
    }

    private void handleClearForm() {
        txtKey.setText("");
        txtValue.setText("");
        if (keyTable != null) {
            keyTable.clearSelection();
        }
        lblHashRouteInfo.setText("Nhập Key để xem định tuyến Server chính và Server dự phòng...");
        lblHashRouteInfo.setForeground(new Color(21, 101, 192));
        lblLastResponseStatus.setText("Đã xóa form.");
        lblLastResponseStatus.setForeground(new Color(33, 33, 33));
        lblServedBy.setText("");
        lblFailoverBadge.setVisible(false);
        lblFeedbackStatus.setText("Sẵn sàng thao tác");
        lblFeedbackStatus.setForeground(COLOR_SUCCESS_TEXT);
        txtKey.requestFocusInWindow();
    }

    private void executeAsync(String command, String key, String value, String requestLine, java.util.function.Consumer<CacheClient.Response> callback) {
        if (!isExecuting.compareAndSet(false, true)) {
            return;
        }

        setFormEnabled(false);
        lblFeedbackStatus.setText("Đang gửi lệnh " + command + "...");
        lblFeedbackStatus.setForeground(new Color(21, 101, 192));

        SwingWorker<CacheClient.Response, Void> worker = new SwingWorker<>() {
            @Override
            protected CacheClient.Response doInBackground() {
                if (cacheClient == null) {
                    return new CacheClient.Response("ERROR|E004|CacheClient chua duoc khoi tao", -1, false);
                }
                return cacheClient.send(key, requestLine);
            }

            @Override
            protected void done() {
                try {
                    CacheClient.Response resp = get();
                    boolean success = resp.line() != null && !resp.line().startsWith("ERROR|");
                    displayResponseResult(resp.line(), resp.servedBy(), resp.failedOver(), success);
                    historyModel.logCommand(command, key, value, resp.servedBy(), resp.failedOver(), resp.line());
                    if (callback != null) {
                        callback.accept(resp);
                    }
                    if (resp.line() != null && resp.line().startsWith("NOTFOUND")) {
                        lblFeedbackStatus.setText("Không tìm thấy key: " + key + " (NOTFOUND)");
                        lblFeedbackStatus.setForeground(COLOR_WARN_TEXT);
                    } else if (resp.line() != null && (resp.line().startsWith("OK") || resp.line().startsWith("VALUE|"))) {
                        lblFeedbackStatus.setText("Hoàn thành lệnh " + command);
                        lblFeedbackStatus.setForeground(COLOR_SUCCESS_TEXT);
                    } else {
                        lblFeedbackStatus.setText("Lỗi thực thi lệnh " + command);
                        lblFeedbackStatus.setForeground(COLOR_DANGER_TEXT);
                    }
                } catch (Exception e) {
                    String err = "ERROR|E004|" + e.getMessage();
                    displayResponseResult(err, -1, false, false);
                    historyModel.logCommand(command, key, value, -1, false, err);
                    lblFeedbackStatus.setText("Lỗi thực thi: " + e.getMessage());
                    lblFeedbackStatus.setForeground(COLOR_DANGER_TEXT);
                } finally {
                    isExecuting.set(false);
                    setFormEnabled(true);
                }
            }
        };

        worker.execute();
    }

    private void displayResponseResult(String responseLine, int servedBy, boolean failedOver, boolean success) {
        lblLastResponseStatus.setText("Kết quả: " + responseLine);
        if (responseLine != null && responseLine.startsWith("NOTFOUND")) {
            lblLastResponseStatus.setForeground(COLOR_WARN_TEXT);
        } else if (success) {
            lblLastResponseStatus.setForeground(COLOR_SUCCESS_TEXT);
        } else {
            lblLastResponseStatus.setForeground(COLOR_DANGER_TEXT);
        }

        if (servedBy >= 0 && cacheClient != null && cacheClient.router() != null) {
            ServerConfig.ServerAddress addr = cacheClient.router().server(servedBy);
            lblServedBy.setText("Phục vụ bởi: Server #" + servedBy + " (" + addr.host() + ":" + addr.port() + ")");
        } else {
            lblServedBy.setText("");
        }

        if (failedOver) {
            lblFailoverBadge.setText(" [DỰ PHÒNG - FAILOVER] ");
            lblFailoverBadge.setBackground(COLOR_WARN_BG);
            lblFailoverBadge.setForeground(COLOR_WARN_TEXT);
            lblFailoverBadge.setBorder(new CompoundBorder(
                    new LineBorder(COLOR_WARN_TEXT, 1, true),
                    new EmptyBorder(2, 6, 2, 6)
            ));
            lblFailoverBadge.setVisible(true);
        } else {
            lblFailoverBadge.setVisible(false);
        }
    }

    private void setFormEnabled(boolean enabled) {
        btnPut.setEnabled(enabled);
        btnGet.setEnabled(enabled);
        btnDelete.setEnabled(enabled);
        btnInfo.setEnabled(enabled);
        btnClear.setEnabled(enabled);
    }

    // =========================================================================
    // Table Models
    // =========================================================================

    private static class KeyExplorerTableModel extends AbstractTableModel {
        private final String[] columns = {"Key (Khóa)", "Value (Giá trị)", "Server chính", "Server dự phòng", "Cập nhật lúc"};
        private List<KeyEntry> entries = List.of();

        public void setEntries(List<KeyEntry> newEntries) {
            this.entries = (newEntries != null) ? List.copyOf(newEntries) : List.of();
            fireTableDataChanged();
        }

        public KeyEntry getEntry(int row) {
            if (row >= 0 && row < entries.size()) return entries.get(row);
            return null;
        }

        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= entries.size()) return "";
            KeyEntry k = entries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> k.key();
                case 1 -> k.value();
                case 2 -> "Server #" + k.primaryIndex();
                case 3 -> "Server #" + k.replicaIndex();
                case 4 -> k.lastUpdatedTime();
                default -> "";
            };
        }
    }

    private static class CommandLogTableModel extends AbstractTableModel {
        private final String[] columns = {"Thời gian", "Lệnh", "Key", "Giá trị", "Server phục vụ", "Failover?", "Kết quả trả về"};
        private List<CommandRecord> logs = List.of();

        public void setLogs(List<CommandRecord> newLogs) {
            this.logs = (newLogs != null) ? List.copyOf(newLogs) : List.of();
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return logs.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= logs.size()) return "";
            CommandRecord c = logs.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> c.timestamp();
                case 1 -> c.command();
                case 2 -> c.key();
                case 3 -> c.value() != null ? c.value() : "—";
                case 4 -> c.servedBy() >= 0 ? "Server #" + c.servedBy() : "—";
                case 5 -> c.failedOver() ? "● CÓ (Dự phòng)" : "Không";
                case 6 -> c.responseLine();
                default -> "";
            };
        }
    }
}
