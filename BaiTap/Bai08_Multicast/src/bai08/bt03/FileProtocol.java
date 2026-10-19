package bai08.bt03;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * BAI08_BT03 - Dinh dang goi tin dung chung cho FileSender va FileReceiver.
 *
 * Moi goi tin bat dau bang: MAGIC (int) | TYPE (byte) | fileId (long)
 *
 * Kenh multicast (sender -> tat ca receiver):
 *   META : fileName(UTF) | fileSize(long) | chunkSize(int) | totalChunks(int) | sha256(UTF) | controlPort(int)
 *   DATA : seq(int) | totalChunks(int) | offset(long) | crc32(long) | length(int) | bytes[length]
 *   END  : totalChunks(int) | controlPort(int)
 *
 * Kenh dieu khien unicast (receiver -> sender, cong controlPort):
 *   NACK : receiverId(int) | count(int) | seq[count]     xin gui lai cac goi bi mat
 *   DONE : receiverId(int)                               da nhan du va kiem tra SHA-256 OK
 */
public final class FileProtocol {

    public static final String GROUP = "224.1.1.1";
    public static final int PORT = 4444;          // cong multicast nhan du lieu
    public static final int CONTROL_PORT = 4445;  // cong unicast cua sender nhan NACK/DONE
    public static final int CHUNK_SIZE = 1024;    // so byte du lieu file trong 1 goi DATA
    public static final int MAX_PACKET = 65507;   // kich thuoc toi da cua 1 datagram UDP/IPv4
    public static final int MAX_NACK_SEQS = 256;  // so seq toi da trong 1 goi NACK

    public static final int MAGIC = 0xB08F11E5;
    public static final byte META = 1;
    public static final byte DATA = 2;
    public static final byte END = 3;
    public static final byte NACK = 4;
    public static final byte DONE = 5;

    private FileProtocol() {
    }

    /** Thong tin chung cua mot goi tin sau khi giai ma. */
    public static final class Packet {
        public byte type;
        public long fileId;
        // META
        public String fileName;
        public long fileSize;
        public int chunkSize;
        public String sha256;
        public int controlPort;
        // META / DATA / END
        public int totalChunks;
        // DATA
        public int seq;
        public long offset;
        public long crc32;
        public byte[] data;
        // NACK / DONE
        public int receiverId;
        public int[] missing;
    }

    private static DataOutputStream header(ByteArrayOutputStream bos, byte type, long fileId) throws IOException {
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeByte(type);
        out.writeLong(fileId);
        return out;
    }

    public static byte[] meta(long fileId, String fileName, long fileSize, int chunkSize, int totalChunks,
                              String sha256, int controlPort) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = header(bos, META, fileId);
        out.writeUTF(fileName);
        out.writeLong(fileSize);
        out.writeInt(chunkSize);
        out.writeInt(totalChunks);
        out.writeUTF(sha256);
        out.writeInt(controlPort);
        return bos.toByteArray();
    }

    public static byte[] data(long fileId, int seq, int totalChunks, long offset, long crc32, byte[] buf, int len)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(len + 48);
        DataOutputStream out = header(bos, DATA, fileId);
        out.writeInt(seq);
        out.writeInt(totalChunks);
        out.writeLong(offset);
        out.writeLong(crc32);
        out.writeInt(len);
        out.write(buf, 0, len);
        return bos.toByteArray();
    }

    public static byte[] end(long fileId, int totalChunks, int controlPort) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = header(bos, END, fileId);
        out.writeInt(totalChunks);
        out.writeInt(controlPort);
        return bos.toByteArray();
    }

    public static byte[] nack(long fileId, int receiverId, List<Integer> missing) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = header(bos, NACK, fileId);
        out.writeInt(receiverId);
        out.writeInt(missing.size());
        for (int seq : missing) {
            out.writeInt(seq);
        }
        return bos.toByteArray();
    }

    public static byte[] done(long fileId, int receiverId) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = header(bos, DONE, fileId);
        out.writeInt(receiverId);
        return bos.toByteArray();
    }

    /** Giai ma goi tin; tra ve null neu khong phai goi tin cua giao thuc nay. */
    public static Packet parse(byte[] buf, int offset, int length) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf, offset, length))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            Packet p = new Packet();
            p.type = in.readByte();
            p.fileId = in.readLong();
            switch (p.type) {
                case META -> {
                    p.fileName = in.readUTF();
                    p.fileSize = in.readLong();
                    p.chunkSize = in.readInt();
                    p.totalChunks = in.readInt();
                    p.sha256 = in.readUTF();
                    p.controlPort = in.readInt();
                }
                case DATA -> {
                    p.seq = in.readInt();
                    p.totalChunks = in.readInt();
                    p.offset = in.readLong();
                    p.crc32 = in.readLong();
                    int len = in.readInt();
                    if (len < 0 || len > length) {
                        return null;
                    }
                    p.data = new byte[len];
                    in.readFully(p.data);
                }
                case END -> {
                    p.totalChunks = in.readInt();
                    p.controlPort = in.readInt();
                }
                case NACK -> {
                    p.receiverId = in.readInt();
                    int count = in.readInt();
                    if (count < 0 || count > MAX_NACK_SEQS) {
                        return null;
                    }
                    p.missing = new int[count];
                    for (int i = 0; i < count; i++) {
                        p.missing[i] = in.readInt();
                    }
                }
                case DONE -> p.receiverId = in.readInt();
                default -> {
                    return null;
                }
            }
            return p;
        } catch (IOException e) {
            return null; // goi tin bi cat cut / sai dinh dang
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
