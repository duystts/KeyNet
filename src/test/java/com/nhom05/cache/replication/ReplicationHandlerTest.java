package com.nhom05.cache.replication;

import static org.junit.jupiter.api.Assertions.*;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.server.KeyValueStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        backup.startListener(0); // port 0: he dieu hanh chon cong trong
        primary = new ReplicationHandler(primaryStore);
        primary.setBackup("127.0.0.1", backup.listenerPort());
    }

    @AfterEach
    void tearDown() {
        primary.close();
        backup.close();
        primaryStore.close();
        backupStore.close();
    }

    private ReplicationHandler handlerTo(int port) {
        ReplicationHandler h = new ReplicationHandler(primaryStore);
        h.setBackup("127.0.0.1", port);
        return h;
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
    }

    @Test
    void replicatePutDuaDuLieuSangBackup() throws Exception {
        primary.replicatePut("k1", "v1", 1);
        assertTrue(waitFor(() -> "v1".equals(backupStore.get("k1"))));
    }

    @Test
    void replicatePutGhiDe() throws Exception {
        primary.replicatePut("k", "a", 1);
        assertTrue(waitFor(() -> "a".equals(backupStore.get("k"))));
        primary.replicatePut("k", "b", 2);
        assertTrue(waitFor(() -> "b".equals(backupStore.get("k"))));
    }

    @Test
    void replicateDeleteXoaTrenBackup() throws Exception {
        long ts = backupStore.put("k2", "v2");
        primary.replicateDelete("k2", ts);
        assertTrue(waitFor(() -> backupStore.get("k2") == null));
    }

    @Test
    void keyValueKhongHopLeTraE001() {
        // value chua '|' bi tach thanh thua truong; key/value qua dai bi chan nhu ben client
        assertTrue(backup.apply("REPLICATE|PUT|k|a|b|1").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT||v|1").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT|" + "k".repeat(257) + "|v|1").startsWith("ERROR|E001|"));
        assertTrue(backup.apply("REPLICATE|PUT|k|" + "v".repeat(4097) + "|1").startsWith("ERROR|E001|"));
        assertNull(backupStore.get("k"));
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
        try (Socket s = new Socket("127.0.0.1", backup.listenerPort())) {
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
            try (ReplicationHandler h = handlerTo(fake.getLocalPort())) {
                h.replicatePut("key", "val", 1000);
                assertTrue(done.await(3, TimeUnit.SECONDS) || got[0] != null);
                h.replicateDelete("key", 1001);
                assertTrue(done.await(3, TimeUnit.SECONDS));
            }
            // timestamp gui di dung la timestamp truyen vao, khong tu lay gio luc gui
            assertEquals("REPLICATE|PUT|key|val|1000", got[0]);
            assertEquals("REPLICATE|DELETE|key|1001", got[1]);
        }
    }

    @Test
    void backupChetKhongNemNgoaiLeVaKhongChan() throws Exception {
        int deadPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            deadPort = tmp.getLocalPort(); // dong ngay -> khong ai lang nghe
        }
        try (ReplicationHandler h = handlerTo(deadPort)) {
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
            assertDoesNotThrow(() -> h.replicatePut("a", "b", 1));
            assertDoesNotThrow(() -> h.replicateDelete("a", 1));
        }
    }

    @Test
    void replicateKhongChanKhiBackupPhanHoiCham() throws Exception {
        // server gia nhan ket noi nhung khong tra loi
        try (ServerSocket mute = new ServerSocket(0);
             ReplicationHandler h = handlerTo(mute.getLocalPort())) {
            long start = System.nanoTime();
            h.replicatePut("a", "b", 1);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 300, "bi chan " + ms + "ms");
        }
    }

    @Test
    void hangDoiGuiCoGioiHanKhiBackupKhongTraLoi() throws Exception {
        // Backup nhan ket noi nhung khong tra loi -> moi lan gui giu thread toi 2 giay
        try (ServerSocket mute = new ServerSocket(0);
             ReplicationHandler h = new ReplicationHandler(primaryStore, 5)) {
            h.setBackup("127.0.0.1", mute.getLocalPort());
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                h.replicatePut("k" + i, "v", i);
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 1000, "replicate khong duoc chan khi hang doi day, mat " + ms + "ms");
            assertTrue(h.pendingSends() <= 5, "hang doi phai bi gioi han, dang co " + h.pendingSends());
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
    void replicationPutCuDenSauPutMoiBiBoQua() {
        // PUT moi timestamp 200 den truoc
        assertEquals("OK", backup.apply("REPLICATE|PUT|k|new_val|200"));
        assertEquals("new_val", backupStore.get("k"));

        // PUT cu timestamp 100 den sau -> bi bo qua
        assertEquals("OK", backup.apply("REPLICATE|PUT|k|old_val|100"));
        assertEquals("new_val", backupStore.get("k"));
    }

    @Test
    void replicationDeleteRoiPutCuDenTreBiChan() {
        // PUT ban dau timestamp 100
        assertEquals("OK", backup.apply("REPLICATE|PUT|k|v1|100"));
        assertEquals("v1", backupStore.get("k"));

        // DELETE tai timestamp 200 (tao tombstone)
        assertEquals("OK", backup.apply("REPLICATE|DELETE|k|200"));
        assertNull(backupStore.get("k"));

        // PUT cu timestamp 150 den tre -> bi chan boi tombstone, key khong song lai
        assertEquals("OK", backup.apply("REPLICATE|PUT|k|v_old|150"));
        assertNull(backupStore.get("k"));
    }

    @Test
    void replicationDeleteCuHonBanDangCoBiBoQua() {
        // Ban ghi moi co timestamp 200
        assertEquals("OK", backup.apply("REPLICATE|PUT|k|current|200"));
        assertEquals("current", backupStore.get("k"));

        // DELETE cu timestamp 100 den sau -> bi bo qua, key van con
        assertEquals("OK", backup.apply("REPLICATE|DELETE|k|100"));
        assertEquals("current", backupStore.get("k"));
    }

    @Test
    void syncTraVeToanBoSnapshotVaEnd() throws Exception {
        backupStore.put("k1", "v1");
        backupStore.put("k2", "v2");
        try (Socket s = new Socket("127.0.0.1", backup.listenerPort())) {
            s.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("SYNC");
            java.util.List<String> received = new java.util.ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if ("END".equals(line)) {
                    break;
                }
                received.add(line);
            }
            assertEquals(2, received.size());
            assertTrue(received.stream().anyMatch(l -> l.startsWith("REPLICATE|PUT|k1|v1|")));
            assertTrue(received.stream().anyMatch(l -> l.startsWith("REPLICATE|PUT|k2|v2|")));
        }
    }

    @Test
    void syncFromLocPhanVungVaNapVaoStore() throws Exception {
        backupStore.put("keep_me", "val1");
        backupStore.put("drop_me", "val2");
        KeyValueStore freshStore = new KeyValueStore(60, 100);
        try (ReplicationHandler freshHandler = new ReplicationHandler(freshStore)) {
            int loaded = freshHandler.syncFrom("127.0.0.1", backup.listenerPort(), k -> k.startsWith("keep_"));
            assertEquals(1, loaded);
            assertEquals("val1", freshStore.get("keep_me"));
            assertNull(freshStore.get("drop_me"));
        } finally {
            freshStore.close();
        }
    }

    @Test
    void recoverAnToanKhiServerChuaBat() {
        var servers = java.util.List.of(
                new com.nhom05.cache.common.ServerConfig.ServerAddress("127.0.0.1", 20000),
                new com.nhom05.cache.common.ServerConfig.ServerAddress("127.0.0.1", 20001),
                new com.nhom05.cache.common.ServerConfig.ServerAddress("127.0.0.1", 20002)
        );
        com.nhom05.cache.common.ServerConfig config = com.nhom05.cache.common.ServerConfig.of(servers, 1);
        int recovered = primary.recover(config);
        assertEquals(0, recovered, "Khi cac peer chua bat, recover phai an toan tra ve 0");
    }

    private String sendLine(int port, String line) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(line);
            return in.readLine();
        }
    }

    @Test
    void shutdownTuLocalhostTraOkRoiChayAction() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        backup.setShutdownAction(stopped::countDown);
        assertEquals("OK", sendLine(backup.listenerPort(), "SHUTDOWN"));
        assertTrue(stopped.await(2, TimeUnit.SECONDS), "action phai duoc goi sau khi tra OK");
    }

    @Test
    void shutdownKhiChuaChoPhepBiTuChoi() throws Exception {
        assertTrue(sendLine(backup.listenerPort(), "SHUTDOWN").startsWith("ERROR|E001|"));
        // listener van hoat dong binh thuong
        assertEquals("OK", sendLine(backup.listenerPort(), "REPLICATE|PUT|k|v|1"));
    }

    @Test
    void testRoutingLogicWhenOriginIsNotPrimary() throws Exception {
        // Mô phỏng cụm 3 server: S0, S1, S2
        // Handler đang test đóng vai trò Server #1 (serverIndex = 1)
        List<String> receivedOnServer0 = Collections.synchronizedList(new ArrayList<>());
        List<String> receivedOnServer2 = Collections.synchronizedList(new ArrayList<>());

        try (ServerSocket fakeS0 = new ServerSocket(0);
             ServerSocket fakeS2 = new ServerSocket(0)) {

            int portS0 = fakeS0.getLocalPort();
            int portS2 = fakeS2.getLocalPort();

            // Client ports tương ứng theo quy ước: client port = rep port - 1000
            int clientPort0 = portS0 - ServerConfig.REPLICATION_PORT_OFFSET;
            int clientPort1 = 20001;
            int clientPort2 = portS2 - ServerConfig.REPLICATION_PORT_OFFSET;

            List<ServerConfig.ServerAddress> cluster = List.of(
                    new ServerConfig.ServerAddress("127.0.0.1", clientPort0),
                    new ServerConfig.ServerAddress("127.0.0.1", clientPort1),
                    new ServerConfig.ServerAddress("127.0.0.1", clientPort2)
            );

            ServerConfig config1 = ServerConfig.of(cluster, 1);

            // Accept loop cho fake S0
            Thread t0 = new Thread(() -> {
                while (!fakeS0.isClosed()) {
                    try (Socket s = fakeS0.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        receivedOnServer0.add(in.readLine());
                        s.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().flush();
                    } catch (Exception ignored) {
                        break;
                    }
                }
            });
            t0.setDaemon(true);
            t0.start();

            // Accept loop cho fake S2
            Thread t2 = new Thread(() -> {
                while (!fakeS2.isClosed()) {
                    try (Socket s = fakeS2.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        receivedOnServer2.add(in.readLine());
                        s.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().flush();
                    } catch (Exception ignored) {
                        break;
                    }
                }
            });
            t2.setDaemon(true);
            t2.start();

            KeyValueStore store1 = new KeyValueStore(60, 100);
            try (ReplicationHandler handler1 = new ReplicationHandler(store1, config1)) {
                handler1.setBackup("127.0.0.1", portS2);
                // Tìm key có primary = 0 (k0) và key có primary = 1 (k1)
                String keyPrimary0 = null;
                String keyPrimary1 = null;
                for (int seq = 1; seq < 1000; seq++) {
                    String candidate = "key_" + seq;
                    int p = HashUtil.computeServerIndex(candidate, 3);
                    if (p == 0 && keyPrimary0 == null) {
                        keyPrimary0 = candidate;
                    } else if (p == 1 && keyPrimary1 == null) {
                        keyPrimary1 = candidate;
                    }
                    if (keyPrimary0 != null && keyPrimary1 != null) {
                        break;
                    }
                }
                assertNotNull(keyPrimary0);
                assertNotNull(keyPrimary1);
                final String k0 = keyPrimary0;
                final String k1 = keyPrimary1;

                // 1. Kiểm tra logic vai trò
                assertFalse(handler1.isPrimary(k0), "S1 khong phai primary cua keyPrimary0");
                assertEquals(0, handler1.getPrimaryForKey(k0));

                assertTrue(handler1.isPrimary(k1), "S1 la primary cua keyPrimary1");
                assertEquals(1, handler1.getPrimaryForKey(k1));

                // 2. Ghi keyPrimary0 (failover path): REPLICATE phai gui ve Server 0, KHONG gui toi Server 2
                handler1.replicatePut(k0, "val0", 1001L);
                assertTrue(waitFor(() -> receivedOnServer0.stream().anyMatch(l -> l.contains(k0))));
                assertTrue(receivedOnServer0.get(0).startsWith("REPLICATE|PUT|" + k0 + "|val0|1001"));
                assertEquals(0, receivedOnServer2.size(), "Server 2 khong duoc nhan REPLICATE cua keyPrimary0");

                handler1.replicateDelete(k0, 1002L);
                assertTrue(waitFor(() -> receivedOnServer0.stream().anyMatch(l -> l.startsWith("REPLICATE|DELETE|" + k0))));
                assertEquals(0, receivedOnServer2.size(), "Server 2 van khong nhan duoc gi");

                // 3. Ghi keyPrimary1 (primary path tren S1): REPLICATE phai gui toi replica tiep theo la Server 2
                handler1.replicatePut(k1, "val1", 2001L);
                assertTrue(waitFor(() -> receivedOnServer2.stream().anyMatch(l -> l.contains(k1))));
                assertTrue(receivedOnServer2.get(0).startsWith("REPLICATE|PUT|" + k1 + "|val1|2001"));
            } finally {
                store1.close();
            }
        }
    }

    @Test
    void testSyncIncludesTombstoneAndPreventsResurrection() throws Exception {
        // backupStore tao 1 key roi xoa de tao tombstone
        long putTs = backupStore.put("k_active", "val_active");
        long delTs = backupStore.nextTimestamp();
        backupStore.delete("k_deleted", delTs);

        // Kiem tra tombstone snapshot
        var tombstones = backupStore.tombstoneSnapshot();
        assertEquals(1, tombstones.size());
        assertEquals("k_deleted", tombstones.get(0).key());
        assertEquals(delTs, tombstones.get(0).timestamp());

        // freshStore gia lap server dang phuc hoi, co ban ghi cu "stale_val"
        KeyValueStore freshStore = new KeyValueStore(60, 100);
        freshStore.putReplicated("k_deleted", "stale_val", delTs - 50);
        assertEquals("stale_val", freshStore.get("k_deleted"));

        try (ReplicationHandler freshHandler = new ReplicationHandler(freshStore)) {
            // syncFrom tu backup: phai nhan ca REPLICATE|PUT va REPLICATE|DELETE
            int loaded = freshHandler.syncFrom("127.0.0.1", backup.listenerPort(), null);
            assertTrue(loaded >= 2, "Nap it nhat 2 ban ghi (1 PUT + 1 DELETE tombstone)");

            assertEquals("val_active", freshStore.get("k_active"));
            assertNull(freshStore.get("k_deleted"), "Key da xoa khong duoc phep ton tai");
            assertEquals(delTs, freshStore.getTombstoneTimestamp("k_deleted"));

            // Thu ghi de ban ghi cu hon tombstone -> phai bi tu choi, key khong song lai
            boolean resurrected = freshStore.putReplicated("k_deleted", "resurrected", delTs - 10);
            assertFalse(resurrected, "PUT voi timestamp cu hon tombstone phai bi bo qua");
            assertNull(freshStore.get("k_deleted"), "Key van phai la null");
        } finally {
            freshStore.close();
        }
    }
}
