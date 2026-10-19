package bai08.bt03;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * BAI08_BT03 - May nhan file qua Multicast.
 *
 * - Tham gia nhom multicast, nhan cac goi DATA (co the den sai thu tu / trung lap / bi mat).
 * - Moi goi DATA mang so thu tu seq va vi tri offset: ghi thang vao dung vi tri trong file tam
 *   bang RandomAccessFile -> file luon duoc tai cau truc dung thu tu.
 * - BitSet danh dau goi da nhan; goi trung lap bi bo qua; goi sai CRC32 coi nhu bi mat.
 * - Khi nhan END (hoac khong co goi moi trong 1 giay) ma van thieu goi -> gui NACK (unicast) ve
 *   may gui de xin gui lai. Nhan du thi kiem tra SHA-256, doi ten file va gui DONE.
 *
 * Cach chay: java bai08.bt03.FileReceiver [thuMucLuu] [tiLeMatGoiGiaLap] [diaChiNhom] [cong]
 *   tiLeMatGoiGiaLap (0.0 - 0.9): co y bo bot goi DATA de thu nghiem co che xu ly mat goi.
 */
public class FileReceiver {

    private static final long NACK_INTERVAL_MS = 200;  // khong gui NACK day dac hon muc nay
    private static final long IDLE_NACK_MS = 1000;     // 1s khong co goi moi ma con thieu -> NACK
    private static final long GIVE_UP_MS = 30000;      // 30s khong nhan duoc gi -> bo cuoc
    private static final int MAX_NACK_PACKETS = 4;     // moi lan xin toi da 4 x 256 goi

    private final File outDir;
    private final double lossRate;
    private final InetSocketAddress group;
    private final int receiverId = new Random().nextInt(Integer.MAX_VALUE);
    private final Random random = new Random();
    private final CRC32 crc = new CRC32();

    // Trang thai cua file dang nhan
    private long fileId = -1;
    private int totalChunks = -1;
    private BitSet received;
    private int receivedCount = 0;
    private FileProtocol.Packet meta;
    private boolean endSeen = false;
    private InetAddress senderAddr;
    private int controlPort = FileProtocol.CONTROL_PORT;
    private File partFile;
    private RandomAccessFile raf;
    private int duplicates = 0, dropped = 0, corrupted = 0, nackCount = 0, lastPercent = -10;
    private long lastNack = 0;

    public FileReceiver(File outDir, double lossRate, InetSocketAddress group) {
        this.outDir = outDir;
        this.lossRate = lossRate;
        this.group = group;
    }

    public void run() throws IOException {
        outDir.mkdirs();
        try (MulticastSocket socket = new MulticastSocket(group.getPort())) {
            socket.setReceiveBufferSize(4 * 1024 * 1024); // bo dem lon de bot mat goi khi gui nhanh
            socket.joinGroup(group, null);
            socket.setSoTimeout(300);
            System.out.printf("May nhan #%d da tham gia %s:%d, dang cho file...%n",
                    receiverId, group.getAddress().getHostAddress(), group.getPort());

            byte[] buf = new byte[FileProtocol.MAX_PACKET];
            long lastPacket = System.currentTimeMillis();
            long startTime = 0;

            while (!isComplete()) {
                long now = System.currentTimeMillis();
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException timeout) {
                    if (fileId != -1 && now - lastPacket >= IDLE_NACK_MS) {
                        sendNack(socket);
                    }
                    if (fileId != -1 && now - lastPacket > GIVE_UP_MS) {
                        System.out.println("Qua lau khong nhan duoc du lieu, huy nhan file.");
                        cleanup();
                        return;
                    }
                    continue;
                }
                FileProtocol.Packet pk = FileProtocol.parse(p.getData(), p.getOffset(), p.getLength());
                if (pk == null || (fileId != -1 && pk.fileId != fileId)) {
                    continue; // khong phai goi tin cua file dang nhan
                }
                lastPacket = now;
                if (fileId == -1) {
                    fileId = pk.fileId;
                    startTime = now;
                }
                senderAddr = p.getAddress();

                switch (pk.type) {
                    case FileProtocol.META -> onMeta(pk);
                    case FileProtocol.DATA -> onData(pk);
                    case FileProtocol.END -> {
                        controlPort = pk.controlPort;
                        ensureStorage(pk.totalChunks);
                        if (!endSeen) {
                            endSeen = true;
                            System.out.printf("Nhan END: da co %d/%d goi%n", receivedCount, totalChunks);
                        }
                        sendNack(socket);
                    }
                    default -> { }
                }
            }

            finish(socket, System.currentTimeMillis() - startTime);
            socket.leaveGroup(group, null);
        }
    }

    private void onMeta(FileProtocol.Packet pk) throws IOException {
        if (meta == null) {
            meta = pk;
            controlPort = pk.controlPort;
            System.out.printf("META: %s (%d byte, %d goi), SHA-256=%s%n",
                    pk.fileName, pk.fileSize, pk.totalChunks, pk.sha256);
        }
        ensureStorage(pk.totalChunks);
    }

    private void onData(FileProtocol.Packet pk) throws IOException {
        ensureStorage(pk.totalChunks);
        if (pk.seq < 0 || pk.seq >= totalChunks) {
            return;
        }
        if (received.get(pk.seq)) {
            duplicates++; // goi gui lai cho may khac, minh da co roi
            return;
        }
        if (lossRate > 0 && random.nextDouble() < lossRate) {
            dropped++; // gia lap mat goi
            return;
        }
        crc.reset();
        crc.update(pk.data);
        if (crc.getValue() != pk.crc32) {
            corrupted++; // du lieu hong -> coi nhu mat, se xin lai bang NACK
            return;
        }
        raf.seek(pk.offset); // ghi dung vi tri -> dam bao thu tu du goi den lon xon
        raf.write(pk.data);
        received.set(pk.seq);
        receivedCount++;

        int percent = (int) (100L * receivedCount / totalChunks);
        if (percent >= lastPercent + 10) {
            lastPercent = percent - percent % 10;
            System.out.printf("  ... %d%% (%d/%d goi)%n", percent, receivedCount, totalChunks);
        }
    }

    private void ensureStorage(int total) throws IOException {
        if (received != null) {
            return;
        }
        totalChunks = total;
        received = new BitSet(total);
        partFile = new File(outDir, fileId + ".part");
        raf = new RandomAccessFile(partFile, "rw");
    }

    private boolean isComplete() {
        return meta != null && received != null && receivedCount == totalChunks;
    }

    /** Liet ke cac seq con thieu va gui NACK ve may gui. */
    private void sendNack(MulticastSocket socket) throws IOException {
        long now = System.currentTimeMillis();
        if (received == null || senderAddr == null || now - lastNack < NACK_INTERVAL_MS) {
            return;
        }
        if (receivedCount == totalChunks) {
            return; // du goi, chi con thieu META -> META se duoc nhac lai cung voi END
        }
        lastNack = now;
        int seq = received.nextClearBit(0);
        for (int n = 0; n < MAX_NACK_PACKETS && seq < totalChunks; n++) {
            List<Integer> missing = new ArrayList<>();
            while (seq < totalChunks && missing.size() < FileProtocol.MAX_NACK_SEQS) {
                missing.add(seq);
                seq = received.nextClearBit(seq + 1);
            }
            byte[] data = FileProtocol.nack(fileId, receiverId, missing);
            socket.send(new DatagramPacket(data, data.length, senderAddr, controlPort));
            nackCount++;
        }
        System.out.printf("Gui NACK: con thieu %d goi%n", totalChunks - receivedCount);
    }

    private void finish(MulticastSocket socket, long elapsedMs) throws IOException {
        raf.setLength(meta.fileSize);
        raf.close();
        String sha = FileSender.sha256(partFile);
        if (!sha.equals(meta.sha256)) {
            System.out.println("LOI: SHA-256 khong khop! " + sha);
            return;
        }
        File target = new File(outDir, meta.fileName);
        Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

        byte[] done = FileProtocol.done(fileId, receiverId);
        for (int i = 0; i < 3; i++) { // UDP co the mat -> gui DONE 3 lan
            socket.send(new DatagramPacket(done, done.length, senderAddr, controlPort));
        }
        System.out.printf("HOAN TAT: %s (%d byte) trong %d ms, SHA-256 khop.%n",
                target.getPath(), target.length(), elapsedMs);
        System.out.printf("Thong ke: gia lap mat %d goi, hong %d, trung lap %d, da gui %d goi NACK%n",
                dropped, corrupted, duplicates, nackCount);
    }

    private void cleanup() throws IOException {
        if (raf != null) {
            raf.close();
            Files.deleteIfExists(partFile.toPath());
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "received");
        double loss = args.length > 1 ? Double.parseDouble(args[1]) : 0.0;
        if (loss < 0 || loss > 0.9) {
            System.err.println("tiLeMatGoiGiaLap phai trong khoang 0.0 - 0.9");
            return;
        }
        String groupAddr = args.length > 2 ? args[2] : FileProtocol.GROUP;
        int port = args.length > 3 ? Integer.parseInt(args[3]) : FileProtocol.PORT;
        new FileReceiver(dir, loss, new InetSocketAddress(InetAddress.getByName(groupAddr), port)).run();
    }
}
