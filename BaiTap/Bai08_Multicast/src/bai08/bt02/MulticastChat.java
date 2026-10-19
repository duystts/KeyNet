package bai08.bt02;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * BAI08_BT02 - Ung dung chat nhom bang Multicast.
 *
 * - Moi cua so la mot thanh vien: vua gui vua nhan tren cung mot MulticastSocket.
 * - Luong nhan (receiver thread) chay rieng, block o socket.receive().
 * - Luong gui (sender thread) chay rieng, khong lam treo giao dien khi send().
 * - Giao dien Swing chi duoc cap nhat tren Event Dispatch Thread (SwingUtilities.invokeLater).
 *
 * Dinh dang goi tin (UTF-8):  LOAI|maClient|tenNguoiDung|noiDung
 *   LOAI = JOIN | MSG | LEAVE
 *
 * Cach chay: java bai08.bt02.MulticastChat   (mo nhieu cua so / nhieu may de test)
 */
public class MulticastChat extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final int MAX_PACKET = 8192; // gioi han kich thuoc 1 tin nhan (byte)
    private static final String SEP = "|";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Ma ngau nhien de phan biet cac cua so chat chay tren cung mot may
    private final String clientId = UUID.randomUUID().toString().substring(0, 8);

    final JTextField txtGroup = new JTextField("224.1.1.1");
    final JTextField txtPort = new JTextField("4444");
    final JTextField txtUsername = new JTextField();
    final JButton btnJoin = new JButton("Tham gia");
    final JButton btnSend = new JButton("Send");
    final JTextArea txtMessage = new JTextArea(4, 30);
    final JTextArea txtChat = new JTextArea(10, 30);

    // Mot luong duy nhat chuyen de gui -> cac tin nhan duoc gui dung thu tu nguoi dung bam
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-sender");
        t.setDaemon(true);
        return t;
    });

    private volatile MulticastSocket socket;
    private volatile InetSocketAddress groupAddress;
    private volatile String username;
    private Thread receiverThread;

    public MulticastChat() {
        super("Multicast Group Chat");
        buildUI();
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                leave();
                sendExecutor.shutdown();
                dispose();
            }
        });
        pack();
        setLocationByPlatform(true);
    }

    private void buildUI() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, c, 0, "Group ID", txtGroup);
        addRow(form, c, 1, "Port", txtPort);
        addRow(form, c, 2, "Username", txtUsername);

        c.gridx = 0; c.gridy = 3; c.weightx = 0; c.gridwidth = 2;
        form.add(btnJoin, c);
        c.gridwidth = 1;

        // Nut Send ben trai, o nhap tin nhan ben phai (theo giao dien de bai)
        c.gridx = 0; c.gridy = 4; c.weightx = 0; c.fill = GridBagConstraints.BOTH;
        btnSend.setPreferredSize(new Dimension(90, 80));
        btnSend.setMinimumSize(new Dimension(90, 80));
        form.add(btnSend, c);
        c.gridx = 1; c.weightx = 1;
        txtMessage.setLineWrap(true);
        txtMessage.setWrapStyleWord(true);
        JScrollPane messagePane = new JScrollPane(txtMessage);
        messagePane.setPreferredSize(new Dimension(260, 80));
        messagePane.setMinimumSize(new Dimension(160, 80));
        form.add(messagePane, c);

        // Vung hien thi noi dung nhom chat
        c.gridx = 0; c.gridy = 5; c.gridwidth = 2; c.weighty = 1;
        txtChat.setEditable(false);
        txtChat.setLineWrap(true);
        txtChat.setWrapStyleWord(true);
        form.add(new JScrollPane(txtChat), c);

        getContentPane().add(form, BorderLayout.CENTER);

        btnSend.setEnabled(false);
        btnJoin.addActionListener(e -> {
            if (socket == null) {
                join();
            } else {
                leave();
            }
        });
        btnSend.addActionListener(e -> sendChat());
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        p.add(field, c);
    }

    /** Tao socket, joinGroup va khoi dong luong nhan. */
    void join() {
        String name = txtUsername.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Username.");
            return;
        }
        if (name.contains(SEP)) {
            JOptionPane.showMessageDialog(this, "Username không được chứa ký tự '" + SEP + "'.");
            return;
        }
        try {
            InetAddress group = InetAddress.getByName(txtGroup.getText().trim());
            if (!group.isMulticastAddress()) {
                JOptionPane.showMessageDialog(this, "Group ID phải nằm trong 224.0.0.0 - 239.255.255.255.");
                return;
            }
            int port = Integer.parseInt(txtPort.getText().trim());

            MulticastSocket s = new MulticastSocket(port);
            s.setTimeToLive(1);                                   // chi trong LAN
            s.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true); // nhan ca tin cua chinh minh
            groupAddress = new InetSocketAddress(group, port);
            s.joinGroup(groupAddress, null);

            socket = s;
            username = name;
            setConnectedState(true);
            appendLine("[Hệ thống] Đã tham gia nhóm " + group.getHostAddress() + ":" + port);

            receiverThread = new Thread(this::receiveLoop, "chat-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();

            send("JOIN", "");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Port không hợp lệ.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Không thể tham gia nhóm: " + ex.getMessage());
        }
    }

    /** Gui thong bao LEAVE, roi nhom va dong socket. */
    void leave() {
        MulticastSocket s = socket;
        if (s == null) {
            return;
        }
        try {
            // Gui LEAVE dong bo (truoc khi dong socket)
            byte[] data = encode("LEAVE", "");
            s.send(new DatagramPacket(data, data.length, groupAddress));
            s.leaveGroup(groupAddress, null);
        } catch (IOException ignored) {
            // dang thoat, bo qua loi
        }
        socket = null;
        s.close(); // lam receive() o luong nhan nem SocketException -> luong nhan ket thuc
        setConnectedState(false);
        appendLine("[Hệ thống] Đã rời nhóm.");
    }

    private void sendChat() {
        String text = txtMessage.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (encode("MSG", text).length > MAX_PACKET) {
            JOptionPane.showMessageDialog(this, "Tin nhắn quá dài (tối đa " + MAX_PACKET + " byte).");
            return;
        }
        send("MSG", text);
        txtMessage.setText("");
        txtMessage.requestFocus();
    }

    /** Dua viec gui sang luong gui rieng, khong block giao dien. */
    private void send(String type, String content) {
        MulticastSocket s = socket;
        InetSocketAddress dest = groupAddress;
        byte[] data = encode(type, content);
        sendExecutor.submit(() -> {
            try {
                s.send(new DatagramPacket(data, data.length, dest));
            } catch (IOException ex) {
                appendLine("[Lỗi] Không gửi được: " + ex.getMessage());
            }
        });
    }

    private byte[] encode(String type, String content) {
        return (type + SEP + clientId + SEP + username + SEP + content).getBytes(StandardCharsets.UTF_8);
    }

    /** Luong nhan: lap vo han, moi goi tin nhan duoc thi day len giao dien. */
    private void receiveLoop() {
        MulticastSocket s = socket;
        byte[] buffer = new byte[MAX_PACKET];
        while (!s.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                s.receive(packet);
            } catch (SocketException closed) {
                break; // socket da dong khi roi nhom
            } catch (IOException ex) {
                appendLine("[Lỗi] " + ex.getMessage());
                continue;
            }
            String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            // tach toi da 4 phan: noi dung tin nhan co the chua ky tu '|'
            String[] parts = raw.split("\\|", 4);
            if (parts.length < 4) {
                continue; // goi tin khong dung dinh dang -> bo qua
            }
            String type = parts[0];
            boolean mine = parts[1].equals(clientId);
            String sender = parts[2] + (mine ? " (bạn)" : "");
            String time = LocalTime.now().format(TIME);
            switch (type) {
                case "JOIN" -> appendLine("[" + time + "] * " + sender + " đã tham gia nhóm");
                case "LEAVE" -> appendLine("[" + time + "] * " + sender + " đã rời nhóm");
                case "MSG" -> appendLine("[" + time + "] " + sender + ": " + parts[3]);
                default -> { }
            }
        }
    }

    private void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            txtChat.append(line + "\n");
            txtChat.setCaretPosition(txtChat.getDocument().getLength());
        });
    }

    private void setConnectedState(boolean connected) {
        SwingUtilities.invokeLater(() -> {
            txtGroup.setEditable(!connected);
            txtPort.setEditable(!connected);
            txtUsername.setEditable(!connected);
            btnJoin.setText(connected ? "Rời nhóm" : "Tham gia");
            btnSend.setEnabled(connected);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MulticastChat().setVisible(true));
    }
}
