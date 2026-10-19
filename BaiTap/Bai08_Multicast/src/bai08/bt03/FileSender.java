package bai08.bt03;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * BAI08_BT03 - May chu phan phoi file qua Multicast.
 *
 * 1. Chia file thanh cac goi CHUNK_SIZE byte, danh so thu tu (seq) tu 0.
 * 2. Gui META (ten file, kich thuoc, so goi, SHA-256) -> gui lan luot cac goi DATA -> gui END.
 * 3. Giai doan sua loi: lang nghe NACK tu cac may nhan tren cong CONTROL_PORT (unicast),
 *    goi nao bi bao mat thi gui lai goi do qua multicast (cac may khac da co thi tu bo qua).
 * 4. Ket thuc khi du so may nhan bao DONE, hoac khong con NACK nao trong IDLE_TIMEOUT_MS.
 *
 * Cach chay: java bai08.bt03.FileSender <duongDanFile> [soMayNhan] [diaChiNhom] [cong]
 */
public class FileSender {

    private static final int PACKETS_PER_PAUSE = 32;  // gui 32 goi thi nghi 1 chut
    private static final long PAUSE_MS = 2;           // ~16 MB/s, tranh tran bo dem may nhan
    private static final int META_EVERY = 512;        // nhac lai META cho may vao nhom muon
    private static final long IDLE_TIMEOUT_MS = 5000; // khong co NACK trong 5s -> ket thuc
    private static final long RESEND_GUARD_MS = 100;  // nhieu may cung xin 1 goi -> chi gui lai 1 lan

    private final File file;
    private final InetSocketAddress group;
    private final int expectedReceivers;
    private final long fileId = System.currentTimeMillis();
    private final int totalChunks;
    private final long[] lastSent;
    private int sentSincePause = 0;

    private MulticastSocket mcast;
    private RandomAccessFile raf;
    private final byte[] chunk = new byte[FileProtocol.CHUNK_SIZE];
    private final CRC32 crc = new CRC32();

    public FileSender(File file, InetSocketAddress group, int expectedReceivers) {
        this.file = file;
        this.group = group;
        this.expectedReceivers = expectedReceivers;
        this.totalChunks = (int) ((file.length() + FileProtocol.CHUNK_SIZE - 1) / FileProtocol.CHUNK_SIZE);
        this.lastSent = new long[totalChunks];
    }

    public void run() throws IOException, InterruptedException {
        String sha256 = sha256(file);
        System.out.printf("File: %s (%d byte) -> %d goi x %d byte, SHA-256=%s%n",
                file.getName(), file.length(), totalChunks, FileProtocol.CHUNK_SIZE, sha256);

        try (MulticastSocket m = new MulticastSocket();
             DatagramSocket control = new DatagramSocket(FileProtocol.CONTROL_PORT);
             RandomAccessFile r = new RandomAccessFile(file, "r")) {
            mcast = m;
            raf = r;
            mcast.setTimeToLive(1);
            byte[] meta = FileProtocol.meta(fileId, file.getName(), file.length(), FileProtocol.CHUNK_SIZE,
                    totalChunks, sha256, FileProtocol.CONTROL_PORT);
            byte[] end = FileProtocol.end(fileId, totalChunks, FileProtocol.CONTROL_PORT);

            // ---- Giai doan 1: gui toan bo file ----
            long start = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                send(meta);
            }
            for (int seq = 0; seq < totalChunks; seq++) {
                if (seq > 0 && seq % META_EVERY == 0) {
                    send(meta);
                }
                sendChunk(seq);
            }
            send(end);
            System.out.printf("Da gui %d goi trong %d ms. Chuyen sang giai doan xu ly mat goi...%n",
                    totalChunks, System.currentTimeMillis() - start);

            // ---- Giai doan 2: nhan NACK, gui lai goi bi mat ----
            Set<Integer> doneReceivers = new HashSet<>();
            int resent = 0;
            byte[] buf = new byte[FileProtocol.MAX_PACKET];
            control.setSoTimeout(500);
            long lastActivity = System.currentTimeMillis();

            while (System.currentTimeMillis() - lastActivity < IDLE_TIMEOUT_MS) {
                if (expectedReceivers > 0 && doneReceivers.size() >= expectedReceivers) {
                    break;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    control.receive(p);
                } catch (SocketTimeoutException timeout) {
                    // Nhac lai META + END de may nao lo mat END van biet file da gui xong
                    send(meta);
                    send(end);
                    continue;
                }
                FileProtocol.Packet pk = FileProtocol.parse(p.getData(), p.getOffset(), p.getLength());
                if (pk == null || pk.fileId != fileId) {
                    continue;
                }
                lastActivity = System.currentTimeMillis();
                if (pk.type == FileProtocol.NACK) {
                    int count = 0;
                    for (int seq : pk.missing) {
                        if (seq >= 0 && seq < totalChunks
                                && System.currentTimeMillis() - lastSent[seq] >= RESEND_GUARD_MS) {
                            sendChunk(seq);
                            count++;
                        }
                    }
                    resent += count;
                    send(end);
                    System.out.printf("NACK tu %s#%d: xin %d goi, gui lai %d goi%n",
                            p.getAddress().getHostAddress(), pk.receiverId, pk.missing.length, count);
                } else if (pk.type == FileProtocol.DONE && doneReceivers.add(pk.receiverId)) {
                    System.out.printf("May nhan %s#%d da nhan du file (%d/%s)%n",
                            p.getAddress().getHostAddress(), pk.receiverId, doneReceivers.size(),
                            expectedReceivers > 0 ? String.valueOf(expectedReceivers) : "?");
                }
            }
            System.out.printf("Hoan tat: %d may nhan bao DONE, tong so goi gui lai: %d%n",
                    doneReceivers.size(), resent);
        }
    }

    private void sendChunk(int seq) throws IOException, InterruptedException {
        long offset = (long) seq * FileProtocol.CHUNK_SIZE;
        int len = (int) Math.min(FileProtocol.CHUNK_SIZE, file.length() - offset);
        raf.seek(offset);
        raf.readFully(chunk, 0, len);
        crc.reset();
        crc.update(chunk, 0, len);
        send(FileProtocol.data(fileId, seq, totalChunks, offset, crc.getValue(), chunk, len));
        lastSent[seq] = System.currentTimeMillis();
    }

    private void send(byte[] data) throws IOException, InterruptedException {
        mcast.send(new DatagramPacket(data, data.length, group));
        if (++sentSincePause >= PACKETS_PER_PAUSE) {
            sentSincePause = 0;
            Thread.sleep(PAUSE_MS); // dieu tiet toc do gui
        }
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return FileProtocol.toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Cach dung: java bai08.bt03.FileSender <duongDanFile> [soMayNhan] [diaChiNhom] [cong]");
            return;
        }
        File f = new File(args[0]);
        if (!f.isFile()) {
            System.err.println("Khong tim thay file: " + f.getAbsolutePath());
            return;
        }
        int expected = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        String groupAddr = args.length > 2 ? args[2] : FileProtocol.GROUP;
        int port = args.length > 3 ? Integer.parseInt(args[3]) : FileProtocol.PORT;
        new FileSender(f, new InetSocketAddress(InetAddress.getByName(groupAddr), port), expected).run();
    }
}
