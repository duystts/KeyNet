package com.nhom05.cache.replication;

import static org.junit.jupiter.api.Assertions.*;

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
        backup = new ReplicationHandler(backupStore, null, 0);
        int port = backup.startListener(0); // port 0: he dieu hanh chon cong trong
        primary = new ReplicationHandler(primaryStore, "127.0.0.1", port);
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
        assertTrue(backup.getListenPort() > 0);
    }

    @Test
    void replicatePutDuaDuLieuSangBackup() throws Exception {
        primary.replicatePut("k1", "v1");
        assertTrue(waitFor(() -> "v1".equals(backupStore.get("k1"))));
    }

    @Test
    void replicatePutGhiDe() throws Exception {
        primary.replicatePut("k", "a");
        assertTrue(waitFor(() -> "a".equals(backupStore.get("k"))));
        primary.replicatePut("k", "b");
        assertTrue(waitFor(() -> "b".equals(backupStore.get("k"))));
    }

    @Test
    void replicateDeleteXoaTrenBackup() throws Exception {
        backupStore.put("k2", "v2");
        primary.replicateDelete("k2");
        assertTrue(waitFor(() -> backupStore.get("k2") == null));
    }

    @Test
    void valueChuaDauGachDung() throws Exception {
        primary.replicatePut("k", "a|b|c");
        assertTrue(waitFor(() -> "a|b|c".equals(backupStore.get("k"))));
    }

    @Test
    void applyTraOkVaCapNhatKho() {
        assertEquals("OK", backup.apply("REPLICATE|PUT|x|y|123"));
        assertEquals("y", backupStore.get("x"));
        assertEquals("OK", backup.apply("REPLICATE|DELETE|x|124"));
        assertNull(backupStore.get("x"));
    }

    @Test
    void applyBanTinSaiTraE001() {
        assertTrue(backup.apply("HELLO").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT|k|v").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT|k|v|abc").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|DELETE|k").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|FOO|k|1").startsWith("ERROR|E001|"));
        assertTrue(backup.apply(null).startsWith("ERROR|E001|"));
    }

    @Test
    void listenerTraLoiQuaSocketThat() throws Exception {
        try (Socket s = new Socket("127.0.0.1", backup.getListenPort())) {
            s.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("REPLICATE|PUT|a|b|1");
            assertEquals("OK", in.readLine());
            out.println("garbage");
            assertTrue(in.readLine().startsWith("ERROR|E001|"));
        }
        assertEquals("b", backupStore.get("a"));
    }

    @Test
    void banTinGuiDiDungDinhDang() throws Exception {
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
            try (ReplicationHandler h = new ReplicationHandler(primaryStore, "127.0.0.1", fake.getLocalPort())) {
                h.replicatePut("key", "val");
                assertTrue(done.await(3, TimeUnit.SECONDS) || got[0] != null);
                h.replicateDelete("key");
                assertTrue(done.await(3, TimeUnit.SECONDS));
            }
            assertTrue(got[0].matches("REPLICATE\\|PUT\\|key\\|val\\|\\d+"), got[0]);
            assertTrue(got[1].matches("REPLICATE\\|DELETE\\|key\\|\\d+"), got[1]);
        }
    }

    @Test
    void backupChetKhongNemNgoaiLeVaKhongChan() throws Exception {
        int deadPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            deadPort = tmp.getLocalPort(); // dong ngay -> khong ai lang nghe
        }
        try (ReplicationHandler h = new ReplicationHandler(primaryStore, "127.0.0.1", deadPort)) {
            long start = System.nanoTime();
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 20; i++) {
                    h.replicatePut("k" + i, "v");
                    h.replicateDelete("k" + i);
                }
            });
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 500, "replicate khong duoc chan, mat " + ms + "ms");
        }
    }

    @Test
    void khongCoDoiTacThiBoQua() {
        try (ReplicationHandler h = new ReplicationHandler(primaryStore, null, 0)) {
            assertDoesNotThrow(() -> h.replicatePut("a", "b"));
            assertDoesNotThrow(() -> h.replicateDelete("a"));
        }
    }

    @Test
    void replicateKhongChanKhiBackupPhanHoiCham() throws Exception {
        // server gia nhan ket noi nhung khong tra loi
        try (ServerSocket mute = new ServerSocket(0);
             ReplicationHandler h = new ReplicationHandler(primaryStore, "127.0.0.1", mute.getLocalPort())) {
            long start = System.nanoTime();
            h.replicatePut("a", "b");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 300, "bi chan " + ms + "ms");
        }
    }

    @Test
    void replicationVanTuanThuTtlVaLru() throws Exception {
        try (KeyValueStore small = new KeyValueStore(60, 2);
             ReplicationHandler h = new ReplicationHandler(small, null, 0)) {
            h.apply("REPLICATE|PUT|a|1|1");
            h.apply("REPLICATE|PUT|b|2|2");
            h.apply("REPLICATE|PUT|c|3|3"); // vuot 2 -> LRU xoa a
            assertNull(small.get("a"));
            assertEquals("3", small.get("c"));
        }
    }
}
