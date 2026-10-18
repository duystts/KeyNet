package com.nhom05.cache.replication;

import static org.junit.jupiter.api.Assertions.*;

import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.server.KeyValueStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplicationHandlerTest {

    private KeyValueStore primaryStore;
    private KeyValueStore backupStore;
    private ReplicationHandler backup;
    private ReplicationHandler primary;

    @BeforeEach
    void setUp() throws Exception {
        primaryStore = new KeyValueStore(60, 100);
        backupStore = new KeyValueStore(60, 100);
        backup = new ReplicationHandler(backupStore);
        int port = backup.startListener(0); // port 0: he dieu hanh chon cong trong
        primary = new ReplicationHandler(primaryStore);
        primary.setBackup("127.0.0.1", port);
    }

    @AfterEach
    void tearDown() {
        primary.close();
        backup.close();
        primaryStore.close();
        backupStore.close();
    }

    /** Cho toi da 3 giay den khi dieu kien dung (vi gui bat dong bo). */
    private static boolean waitFor(BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    @Test
    void listenerPortLaCongThuc() {
        assertTrue(backup.listenerPort() > 0);
        assertEquals(-1, primary.listenerPort()); // primary chua mo listener
    }

    @Test
    void replicatePutDuaDuLieuSangBackup() throws Exception {
        long ts = primaryStore.put("k1", "v1");
        primary.replicatePut("k1", "v1", ts);
        assertTrue(waitFor(() -> "v1".equals(backupStore.get("k1"))));
    }

    @Test
    void replicatePutGhiDe() throws Exception {
        primary.replicatePut("k", "a", 1L);
        assertTrue(waitFor(() -> "a".equals(backupStore.get("k"))));
        primary.replicatePut("k", "b", 2L);
        assertTrue(waitFor(() -> "b".equals(backupStore.get("k"))));
    }

    @Test
    void replicateDeleteXoaTrenBackup() throws Exception {
        backupStore.put("k2", "v2");
        primary.replicateDelete("k2", primaryStore.now());
        assertTrue(waitFor(() -> backupStore.get("k2") == null));
    }

    @Test
    void valueChuaDauGachDungBiTuChoiE001() throws Exception {
        // gui thang qua replicatePut: backup phai tu choi, khong ghi vao kho
        primary.replicatePut("k", "a|b|c", 1L);
        Thread.sleep(300);
        assertNull(backupStore.get("k"));
        assertEquals("ERROR|E001|Invalid command syntax", backup.apply("REPLICATE|PUT|k|a|b|c|1"));
    }

    @Test
    void applyTraOkVaCapNhatKho() {
        assertEquals("OK", backup.apply("REPLICATE|PUT|x|y|123"));
        assertEquals("y", backupStore.get("x"));
        assertEquals("OK", backup.apply("REPLICATE|DELETE|x|124"));
        assertNull(backupStore.get("x"));
    }

    @Test
    void applyValueRongHopLe() {
        assertEquals("OK", backup.apply("REPLICATE|PUT|x||5"));
        assertEquals("", backupStore.get("x"));
    }

    @Test
    void applyBanTinSaiTraE001() {
        assertTrue(backup.apply("HELLO").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT|k|v").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT|k|v|abc").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT||v|1").startsWith("ERROR|E001|")); // key rong
        assertTrue(backup.apply("REPLICATE|DELETE|k").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|DELETE||1").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|FOO|k|1").startsWith("ERROR|E001|"));
        assertTrue(backup.apply(null).startsWith("ERROR|E001|"));
        assertTrue(backup.apply("").startsWith("ERROR|E001|"));
    }

    @Test
    void applyKeyQuaDaiTraE002() {
        String key = "k".repeat(ProtocolParser.MAX_KEY_LENGTH + 1);
        assertTrue(backup.apply("REPLICATE|PUT|" + key + "|v|1").startsWith("ERROR|E002|"));
        assertTrue(backup.apply("REPLICATE|DELETE|" + key + "|1").startsWith("ERROR|E002|"));
        assertNull(backupStore.get(key));
    }

    @Test
    void applyValueQuaDaiTraE003() {
        String value = "v".repeat(ProtocolParser.MAX_VALUE_LENGTH + 1);
        assertTrue(backup.apply("REPLICATE|PUT|k|" + value + "|1").startsWith("ERROR|E003|"));
        assertNull(backupStore.get("k"));
    }

    @Test
    void applyDoDaiToiDaVanHopLe() {
        String key = "k".repeat(ProtocolParser.MAX_KEY_LENGTH);
        String value = "v".repeat(ProtocolParser.MAX_VALUE_LENGTH);
        assertEquals("OK", backup.apply("REPLICATE|PUT|" + key + "|" + value + "|1"));
        assertEquals(value, backupStore.get(key));
    }

    @Test
    void listenerTraLoiQuaSocketThat() throws Exception {
        try (Socket s = new Socket("127.0.0.1", backup.listenerPort())) {
            s.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("REPLICATE|PUT|a|b|1");
            assertEquals("OK", in.readLine());
            out.println("garbage");
            assertTrue(in.readLine().startsWith("ERROR|E001|"));
            out.println("REPLICATE|PUT|a|x|y|1"); // value chua dau |
            assertTrue(in.readLine().startsWith("ERROR|E001|"));
        }
        assertEquals("b", backupStore.get("a"));
    }

    @Test
    void listenerKhongMoHaiLan() {
        assertThrows(IllegalStateException.class, () -> backup.startListener(0));
    }

    @Test
    void banTinGuiDiDungDinhDangVaDungTimestampTruyenVao() throws Exception {
        // server gia: ghi lai dong nhan duoc, tra OK
        try (ServerSocket fake = new ServerSocket(0)) {
            String[] got = new String[2];
            CountDownLatch done = new CountDownLatch(2);
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket c = fake.accept()) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    c.getInputStream(), StandardCharsets.UTF_8));
                            got[i] = in.readLine();
                            c.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                            c.getOutputStream().flush();
                        }
                        done.countDown();
                    }
                } catch (Exception ignored) {
                    // test se fail o assert
                }
            });
            t.setDaemon(true);
            t.start();
            try (ReplicationHandler h = new ReplicationHandler(primaryStore)) {
                h.setBackup("127.0.0.1", fake.getLocalPort());
                h.replicatePut("key", "val", 1111L);
                // doi ban tin dau xong roi moi gui ban tin 2 de thu tu on dinh
                assertTrue(waitFor(() -> got[0] != null));
                h.replicateDelete("key", 2222L);
                assertTrue(done.await(3, TimeUnit.SECONDS));
            }
            assertEquals("REPLICATE|PUT|key|val|1111", got[0]);
            assertEquals("REPLICATE|DELETE|key|2222", got[1]);
        }
    }

    @Test
    void backupChetKhongNemNgoaiLeVaKhongChan() throws Exception {
        int deadPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            deadPort = tmp.getLocalPort(); // dong ngay -> khong ai lang nghe
        }
        try (ReplicationHandler h = new ReplicationHandler(primaryStore)) {
            h.setBackup("127.0.0.1", deadPort);
            long start = System.nanoTime();
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 20; i++) {
                    h.replicatePut("k" + i, "v", i);
                    h.replicateDelete("k" + i, i);
                }
            });
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 500, "replicate khong duoc chan, mat " + ms + "ms");
        }
    }

    @Test
    void khongCoDoiTacThiBoQua() {
        try (ReplicationHandler h = new ReplicationHandler(primaryStore)) {
            assertDoesNotThrow(() -> h.replicatePut("a", "b", 1L));
            assertDoesNotThrow(() -> h.replicateDelete("a", 1L));
        }
    }

    @Test
    void gocBackupBangNullThiTatReplication() {
        try (ReplicationHandler h = new ReplicationHandler(primaryStore)) {
            h.setBackup("127.0.0.1", 9);
            h.setBackup(null, 0);
            assertDoesNotThrow(() -> h.replicatePut("a", "b", 1L));
        }
    }

    @Test
    void replicateKhongChanKhiBackupPhanHoiCham() throws Exception {
        // server gia nhan ket noi nhung khong tra loi
        try (ServerSocket mute = new ServerSocket(0);
             ReplicationHandler h = new ReplicationHandler(primaryStore)) {
            h.setBackup("127.0.0.1", mute.getLocalPort());
            long start = System.nanoTime();
            h.replicatePut("a", "b", 1L);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 300, "bi chan " + ms + "ms");
        }
    }

    @Test
    void replicationVanTuanThuTtlVaLru() throws Exception {
        try (KeyValueStore small = new KeyValueStore(60, 2);
             ReplicationHandler h = new ReplicationHandler(small)) {
            h.apply("REPLICATE|PUT|a|1|1");
            h.apply("REPLICATE|PUT|b|2|2");
            h.apply("REPLICATE|PUT|c|3|3"); // vuot 2 -> LRU xoa a
            assertNull(small.get("a"));
            assertEquals("3", small.get("c"));
        }
    }

    @Test
    void parseReplicateDungVaSai() {
        ProtocolParser.Replicate put = ProtocolParser.parseReplicate("REPLICATE|PUT|k|v|9");
        assertEquals(ProtocolParser.ReplicateType.PUT, put.type());
        assertEquals("k", put.key());
        assertEquals("v", put.value());
        assertEquals(9L, put.timestamp());
        ProtocolParser.Replicate del = ProtocolParser.parseReplicate("REPLICATE|DELETE|k|9");
        assertEquals(ProtocolParser.ReplicateType.DELETE, del.type());
        assertNull(del.value());
        assertNull(ProtocolParser.parseReplicate("REPLICATE|PUT|k|v"));
        assertNull(ProtocolParser.parseReplicate("REPLICATE|DELETE|k|1|2"));
        assertNull(ProtocolParser.parseReplicate("REPLICATE|PUT|k|v|x"));
    }
}
