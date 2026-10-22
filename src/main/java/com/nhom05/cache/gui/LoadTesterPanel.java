package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.gui.LoadTester.LoadTestConfig;
import com.nhom05.cache.gui.LoadTester.LoadTestResult;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Module 5 - Panel giao dien kiem thu tai cho he thong KeyNet (Issue #17).
 *
 * Tuan thu dac ta:
 * - N luong x M request (PUT/GET).
 * - Do Throughput (req/s), do tre TB / min / max (ms), ty le thanh cong (%).
 * - Khong lam treo giao dien (chay qua SwingWorker).
 * - Cho phep cau hinh N, M, ty le workload, pham vi key va file bao cao.
 * - Hien thi tien do realtime, the KPI thong so va xem truoc bao cao docs/load_test_report.txt.
 */
public class LoadTesterPanel extends JPanel {

    private static final Color BG_MAIN = new Color(245, 247, 250);
    private static final Color BG_CARD = Color.WHITE;
    private static final Color COLOR_PRIMARY = new Color(33, 150, 243);
    private static final Color COLOR_SUCCESS_TEXT = new Color(46, 125, 50);
    private static final Color COLOR_DANGER_TEXT = new Color(198, 40, 40);
    private static final Color COLOR_BORDER = new Color(224, 224, 224);

    private final CacheClient cacheClient;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    // Form inputs
    private JSpinner spinnerThreads;
    private JSpinner spinnerRequestsPerThread;
    private JComboBox<String> comboWorkload;
    private JSpinner spinnerKeyCount;
    private JTextField txtReportPath;
    private JComboBox<String> cmbTester;
    private JCheckBox chkUsePool;

    // Action buttons
    private JButton btnStart;
    private JButton btnStop;
    private JButton btnOpenReport;

    // Progress components
    private JProgressBar progressBar;
    private JLabel lblStatus;

    // KPI Cards labels
    private JLabel lblThroughput;
    private JLabel lblDuration;
    private JLabel lblLatency;
    private JLabel lblSuccessRate;
    private JLabel lblSuccessRatio;

    // Report / Log Preview
    private JTextArea txtLogArea;

    public LoadTesterPanel(CacheClient cacheClient) {
        this.cacheClient = cacheClient;

        setLayout(new BorderLayout(10, 10));
        setBackground(BG_MAIN);
        setBorder(new EmptyBorder(12, 14, 12, 14));

        initUI();
    }

    private void initUI() {
        // 1. Top Panel: Header + Configuration
        JPanel topPanel = new JPanel(new BorderLayout(0, 8));
        topPanel.setOpaque(false);
        topPanel.add(createHeaderPanel(), BorderLayout.NORTH);
        topPanel.add(createConfigPanel(), BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        // 2. Center Panel: KPI Cards + Report Output Area
        JPanel centerPanel = new JPanel(new BorderLayout(0, 10));
        centerPanel.setOpaque(false);
        centerPanel.add(createKpiCardsPanel(), BorderLayout.NORTH);
        centerPanel.add(createReportPreviewPanel(), BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // 3. Bottom Panel: Progress Bar & Status
        add(createProgressPanel(), BorderLayout.SOUTH);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(8, 2));
        header.setOpaque(false);

        JLabel titleLabel = new JLabel("KIỂM THỬ TẢI CỤM CACHE (LOAD TESTER — ISSUE #17)");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        titleLabel.setForeground(new Color(33, 33, 33));

        JLabel descLabel = new JLabel("Đo lường Throughput (req/s), Độ trễ (ms) và Tỷ lệ thành công theo giao thức PUT/GET trên N luồng.");
        descLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        descLabel.setForeground(new Color(100, 100, 100));

        header.add(titleLabel, BorderLayout.NORTH);
        header.add(descLabel, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 8));
        panel.setBackground(BG_CARD);

        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                " Cấu Hình Tham Số Đo Tải ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(33, 33, 33)
        );
        panel.setBorder(new CompoundBorder(titledBorder, new EmptyBorder(8, 12, 10, 12)));

        JPanel formGrid = new JPanel(new GridLayout(2, 5, 12, 6));
        formGrid.setOpaque(false);

        // Hang 1: Labels
        JLabel lblThreads = new JLabel("Số luồng (N):");
        lblThreads.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JLabel lblReqs = new JLabel("Request / luồng (M):");
        lblReqs.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JLabel lblWorkload = new JLabel("Tỷ lệ workload:");
        lblWorkload.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JLabel lblKeys = new JLabel("Số lượng key:");
        lblKeys.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JLabel lblPath = new JLabel("File xuất báo cáo:");
        lblPath.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        formGrid.add(lblThreads);
        formGrid.add(lblReqs);
        formGrid.add(lblWorkload);
        formGrid.add(lblKeys);
        formGrid.add(lblPath);

        // Hang 2: Controls
        spinnerThreads = new JSpinner(new SpinnerNumberModel(10, 1, 200, 1));
        spinnerThreads.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        spinnerRequestsPerThread = new JSpinner(new SpinnerNumberModel(50, 1, 10000, 10));
        spinnerRequestsPerThread.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        comboWorkload = new JComboBox<>(new String[]{
                "50% PUT / 50% GET (Cân bằng)",
                "100% PUT (Chỉ ghi)",
                "100% GET (Chỉ đọc)",
                "80% GET / 20% PUT (Đọc thực tế)"
        });
        comboWorkload.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        spinnerKeyCount = new JSpinner(new SpinnerNumberModel(20, 1, 100000, 5));
        spinnerKeyCount.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        txtReportPath = new JTextField("docs/load_test_report.txt");
        txtReportPath.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        formGrid.add(spinnerThreads);
        formGrid.add(spinnerRequestsPerThread);
        formGrid.add(comboWorkload);
        formGrid.add(spinnerKeyCount);
        formGrid.add(txtReportPath);

        JPanel centerConfig = new JPanel(new BorderLayout(0, 8));
        centerConfig.setOpaque(false);
        centerConfig.add(formGrid, BorderLayout.NORTH);

        JPanel extraOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 4));
        extraOptions.setOpaque(false);

        JLabel lblTester = new JLabel("Người kiểm thử:");
        lblTester.setFont(new Font("Segoe UI", Font.BOLD, 12));

        String[] groupMembers = {
                "Nguyen Van Nhat (MSSV: 2380601556)",
                "Pham Anh Duy (MSSV: 2380600371)",
                "Ngo Manh Hung (MSSV: 2380600773)",
                "Nguyen Tien Duc (MSSV: 2380600542)",
                "Nguyen Tran Tuan Anh (MSSV: 2380601323)"
        };
        cmbTester = new JComboBox<>(groupMembers);
        cmbTester.setEditable(true);
        cmbTester.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        cmbTester.setPreferredSize(new Dimension(310, 26));

        chkUsePool = new JCheckBox("Sử dụng Connection Pool (Keep-Alive) [Khuyến nghị để đạt 100% tải cao]", true);
        chkUsePool.setFont(new Font("Segoe UI", Font.BOLD, 12));
        chkUsePool.setForeground(new Color(21, 101, 192));
        chkUsePool.setOpaque(false);

        extraOptions.add(lblTester);
        extraOptions.add(cmbTester);
        extraOptions.add(chkUsePool);

        centerConfig.add(extraOptions, BorderLayout.CENTER);
        panel.add(centerConfig, BorderLayout.CENTER);

        // Buttons Bar
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        btnBar.setOpaque(false);

        btnStart = createStyledButton("Bắt Đầu Đo Tải", new Color(46, 125, 50), Color.WHITE, e -> startLoadTest());
        btnStop = createStyledButton("Dừng Đo Tải", new Color(198, 40, 40), Color.WHITE, e -> stopLoadTest());
        btnStop.setEnabled(false);
        btnOpenReport = createStyledButton("Xem File Báo Cáo", new Color(25, 118, 210), Color.WHITE, e -> openReportFile());

        btnBar.add(btnStart);
        btnBar.add(btnStop);
        btnBar.add(btnOpenReport);

        panel.add(btnBar, BorderLayout.SOUTH);
        return panel;
    }

    private JButton createStyledButton(String text, Color bg, Color fg, java.awt.event.ActionListener al) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (!isEnabled()) {
                    g2.setColor(new Color(230, 230, 230));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g2.setColor(new Color(200, 200, 200));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                    setForeground(new Color(130, 130, 130));
                } else {
                    setForeground(fg);
                    if (getModel().isPressed()) {
                        g2.setColor(bg.darker());
                    } else if (getModel().isRollover()) {
                        g2.setColor(bg.brighter());
                    } else {
                        g2.setColor(bg);
                    }
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setForeground(fg);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(8, 16, 8, 16));
        btn.addActionListener(al);
        return btn;
    }

    private JPanel createKpiCardsPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 5, 8, 0));
        panel.setOpaque(false);

        lblThroughput = new JLabel("0.0 req/s", JLabel.CENTER);
        JPanel card1 = createCard("THROUGHPUT", lblThroughput, "requests/giây");

        lblDuration = new JLabel("0 ms", JLabel.CENTER);
        JPanel card2 = createCard("THỜI GIAN", lblDuration, "tổng thời gian chạy");

        lblLatency = new JLabel("0.0 ms", JLabel.CENTER);
        JPanel card3 = createCard("ĐỘ TRỄ TRUNG BÌNH", lblLatency, "Min: -- / Max: --");

        lblSuccessRate = new JLabel("0.0 %", JLabel.CENTER);
        lblSuccessRate.setForeground(COLOR_SUCCESS_TEXT);
        JPanel card4 = createCard("TỶ LỆ THÀNH CÔNG", lblSuccessRate, "chuẩn giao thức");

        lblSuccessRatio = new JLabel("0 / 0", JLabel.CENTER);
        JPanel card5 = createCard("THÀNH CÔNG / TỔNG", lblSuccessRatio, "Lỗi: 0");

        panel.add(card1);
        panel.add(card2);
        panel.add(card3);
        panel.add(card4);
        panel.add(card5);
        return panel;
    }

    private JPanel createCard(String title, JLabel valueLabel, String subtitle) {
        JPanel card = new JPanel(new BorderLayout(2, 4));
        card.setBackground(BG_CARD);
        card.setBorder(new CompoundBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                new EmptyBorder(8, 10, 8, 10)
        ));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        titleLabel.setForeground(new Color(117, 117, 117));
        card.add(titleLabel, BorderLayout.NORTH);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        valueLabel.setForeground(new Color(33, 33, 33));
        card.add(valueLabel, BorderLayout.CENTER);

        JLabel subLabel = new JLabel(subtitle, JLabel.CENTER);
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        subLabel.setForeground(Color.GRAY);
        card.add(subLabel, BorderLayout.SOUTH);

        return card;
    }

    private JPanel createReportPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_CARD);

        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                new LineBorder(COLOR_BORDER, 1, true),
                " Nhật Ký & Nội Dung Báo Cáo Đo Tải (Live Preview) ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(33, 33, 33)
        );
        panel.setBorder(new CompoundBorder(titledBorder, new EmptyBorder(6, 6, 6, 6)));

        txtLogArea = new JTextArea();
        txtLogArea.setEditable(false);
        txtLogArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtLogArea.setBackground(new Color(250, 250, 250));
        txtLogArea.setForeground(new Color(33, 33, 33));
        txtLogArea.setMargin(new Insets(6, 8, 6, 8));
        txtLogArea.setText("Hệ thống kiểm thử tải KeyNet (Nhóm 05) sẵn sàng.\n"
                + "Nhấn nút '▶ Bắt Đầu Đo Tải' để thực thi đo tải và xuất báo cáo ra file.\n");

        JScrollPane scrollPane = new JScrollPane(txtLogArea);
        scrollPane.setBackground(Color.WHITE);
        scrollPane.getViewport().setBackground(new Color(250, 250, 250));
        scrollPane.setBorder(new LineBorder(COLOR_BORDER, 1));

        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createProgressPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("Sẵn sàng đo tải (0%)");
        progressBar.setPreferredSize(new Dimension(100, 22));
        progressBar.setForeground(new Color(33, 150, 243));

        lblStatus = new JLabel("Trạng thái: Sẵn sàng");
        lblStatus.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        lblStatus.setForeground(new Color(100, 100, 100));

        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(lblStatus, BorderLayout.EAST);
        return panel;
    }

    private void startLoadTest() {
        if (!isRunning.compareAndSet(false, true)) {
            return;
        }

        int threads = (int) spinnerThreads.getValue();
        int reqs = (int) spinnerRequestsPerThread.getValue();
        int keyCount = (int) spinnerKeyCount.getValue();
        String reportPath = txtReportPath.getText().trim();
        if (reportPath.isEmpty()) {
            reportPath = "docs/load_test_report.txt";
        }

        int putPct = 50;
        int selectedIndex = comboWorkload.getSelectedIndex();
        if (selectedIndex == 1) putPct = 100;
        else if (selectedIndex == 2) putPct = 0;
        else if (selectedIndex == 3) putPct = 20;

        String testerInfo = cmbTester != null && cmbTester.getSelectedItem() != null
                ? cmbTester.getSelectedItem().toString().trim()
                : LoadTester.DEFAULT_TESTER_INFO;
        if (testerInfo.isEmpty()) {
            testerInfo = LoadTester.DEFAULT_TESTER_INFO;
        }
        boolean usePool = chkUsePool != null && chkUsePool.isSelected();

        LoadTestConfig config = new LoadTestConfig(threads, reqs, putPct, keyCount, reportPath, testerInfo, usePool);

        cancelFlag.set(false);
        setFormEnabled(false);
        btnStop.setEnabled(true);
        progressBar.setValue(0);
        progressBar.setString("0 / " + config.totalRequests() + " requests (0%)");

        txtLogArea.setText("");
        appendLog("Bắt đầu kiểm thử tải với: " + threads + " luồng, " + reqs + " requests/luồng (Tổng: "
                + config.totalRequests() + " requests, Workload: " + putPct + "% PUT / " + (100 - putPct) + "% GET)"
                + ", Kiểm thử viên: " + testerInfo + ", Pool: " + (usePool ? "BẬT" : "TẮT") + ")...");

        final LoadTestConfig finalConfig = config;

        SwingWorker<LoadTestResult, Void> worker = new SwingWorker<>() {
            @Override
            protected LoadTestResult doInBackground() {
                return LoadTester.runTest(cacheClient, finalConfig, (done, total, rate, status) -> {
                    SwingUtilities.invokeLater(() -> {
                        int percent = (int) ((done * 100.0) / total);
                        progressBar.setValue(percent);
                        progressBar.setString(done + " / " + total + " requests (" + percent + "%)");
                        lblStatus.setText(status);
                    });
                }, cancelFlag);
            }

            @Override
            protected void done() {
                try {
                    LoadTestResult result = get();
                    updateKpis(result);
                    appendLog("Kiểm thử tải hoàn tất trong " + result.durationMs() + " ms!");
                    appendLog("Throughput: " + String.format(Locale.US, "%.2f req/s", result.throughput())
                            + " | Độ trễ TB: " + String.format(Locale.US, "%.2f ms", result.avgLatencyMs())
                            + " | Thành công: " + String.format(Locale.US, "%.2f%%", result.successRate()));
                    appendLog("Báo cáo đã được ghi đè vào: " + result.reportPath());

                    // Hien thi noi dung bao cao truc tiep len text area
                    txtLogArea.append("\n" + result.reportContent());
                    txtLogArea.setCaretPosition(0);

                    JOptionPane.showMessageDialog(
                            LoadTesterPanel.this,
                            String.format(Locale.US,
                                    "Đo tải hoàn tất!\n- Throughput: %.2f req/s\n- Độ trễ TB: %.2f ms\n- Tỷ lệ thành công: %.2f%%\n- File báo cáo: %s",
                                    result.throughput(), result.avgLatencyMs(), result.successRate(), result.reportPath()),
                            "Kết quả đo tải (#17)",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (Exception e) {
                    appendLog("LỖI KHI ĐO TẢI: " + e.getMessage());
                    lblStatus.setText("Lỗi kiểm thử tải: " + e.getMessage());
                } finally {
                    isRunning.set(false);
                    setFormEnabled(true);
                    btnStop.setEnabled(false);
                }
            }
        };

        worker.execute();
    }

    private void stopLoadTest() {
        if (isRunning.get()) {
            cancelFlag.set(true);
            appendLog("Người dùng yêu cầu dừng kiểm thử tải...");
            lblStatus.setText("Đang dừng các luồng...");
            btnStop.setEnabled(false);
        }
    }

    private void openReportFile() {
        String reportPath = txtReportPath.getText().trim();
        File file = new File(reportPath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this, "File báo cáo chưa tồn tại: " + reportPath
                    + "\nHãy nhấn 'Bắt Đầu Đo Tải' trước để xuất báo cáo.", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
            } else {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                txtLogArea.setText(content);
                txtLogArea.setCaretPosition(0);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không thể mở file: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateKpis(LoadTestResult r) {
        lblThroughput.setText(String.format(Locale.US, "%.1f req/s", r.throughput()));
        lblDuration.setText(String.format(Locale.US, "%d ms (%.2fs)", r.durationMs(), r.durationMs() / 1000.0));
        lblLatency.setText(String.format(Locale.US, "%.2f ms", r.avgLatencyMs()));
        ((JLabel) ((JPanel) lblLatency.getParent()).getComponent(2)).setText(
                String.format(Locale.US, "Min: %.2f / Max: %.2f", r.minLatencyMs(), r.maxLatencyMs())
        );

        lblSuccessRate.setText(String.format(Locale.US, "%.2f %%", r.successRate()));
        if (r.successRate() >= 99.0) {
            lblSuccessRate.setForeground(COLOR_SUCCESS_TEXT);
        } else if (r.successRate() >= 90.0) {
            lblSuccessRate.setForeground(new Color(230, 81, 0));
        } else {
            lblSuccessRate.setForeground(COLOR_DANGER_TEXT);
        }

        lblSuccessRatio.setText(r.successCount() + " / " + r.totalRequests());
        ((JLabel) ((JPanel) lblSuccessRatio.getParent()).getComponent(2)).setText("Lỗi: " + r.errorCount());
    }

    private void setFormEnabled(boolean enabled) {
        spinnerThreads.setEnabled(enabled);
        spinnerRequestsPerThread.setEnabled(enabled);
        comboWorkload.setEnabled(enabled);
        spinnerKeyCount.setEnabled(enabled);
        txtReportPath.setEnabled(enabled);
        if (cmbTester != null) cmbTester.setEnabled(enabled);
        if (chkUsePool != null) chkUsePool.setEnabled(enabled);
        btnStart.setEnabled(enabled);
        btnOpenReport.setEnabled(enabled);
    }

    private void appendLog(String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        txtLogArea.append("[" + time + "] " + msg + "\n");
        txtLogArea.setCaretPosition(txtLogArea.getDocument().getLength());
    }

    // Getters for Testing
    public JButton btnStart() { return btnStart; }
    public JButton btnStop() { return btnStop; }
    public JProgressBar progressBar() { return progressBar; }
    public JTextArea txtLogArea() { return txtLogArea; }
    public JSpinner spinnerThreads() { return spinnerThreads; }
    public JSpinner spinnerRequestsPerThread() { return spinnerRequestsPerThread; }
    public JComboBox<String> cmbTester() { return cmbTester; }
    public JCheckBox chkUsePool() { return chkUsePool; }
}
