package com.nhom05.cache.replication;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.server.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deliverable cho Issue #10:
 * Test case tu dong gia lap 1 server chet roi phuc hoi du lieu qua co che SYNC & Recover.
 *
 * Kich ban cum 3 server (N = 3):
 * - Server 0 (primary partition 0, backup partition 2)
 * - Server 1 (primary partition 1, backup partition 0)
 * - Server 2 (primary partition 2, backup partition 1)
 */
class ServerCrashRecoveryTest {

    private KeyValueStore store0;
    private KeyValueStore store1;
    private KeyValueStore store2;

    private ReplicationHandler handler0;
    private ReplicationHandler handler1;
    private ReplicationHandler handler2;

    @AfterEach
    void tearDown() {
        if (handler0 != null) handler0.close();
        if (handler1 != null) handler1.close();
        if (handler2 != null) handler2.close();

        if (store0 != null) store0.close();
        if (store1 != null) store1.close();
        if (store2 != null) store2.close();
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static String findKeyForServer(int targetServer, int n, String prefix) {
        int seq = 1;
        while (true) {
            String key = prefix + "_" + seq;
            if (HashUtil.computeServerIndex(key, n) == targetServer) {
                return key;
            }
            seq++;
        }
    }

    @Test
    void testServerChetRoiPhucHoiDuLieuThanhCong() throws Exception {
        int n = 3;

        // Khoi tao 3 store va 3 replication handler
        store0 = new KeyValueStore(60, 1000);
        store1 = new KeyValueStore(60, 1000);
        store2 = new KeyValueStore(60, 1000);

        handler0 = new ReplicationHandler(store0);
        handler1 = new ReplicationHandler(store1);
        handler2 = new ReplicationHandler(store2);

        handler0.startListener(0);
        handler1.startListener(0);
        handler2.startListener(0);

        int repPort0 = handler0.listenerPort();
        int repPort1 = handler1.listenerPort();
        int repPort2 = handler2.listenerPort();

        handler0.setBackup("127.0.0.1", repPort1);
        handler1.setBackup("127.0.0.1", repPort2);
        handler2.setBackup("127.0.0.1", repPort0);

        // Client port gia lap theo dac ta: port client = repPort - 1000
        int clientPort0 = repPort0 - ServerConfig.REPLICATION_PORT_OFFSET;
        int clientPort1 = repPort1 - ServerConfig.REPLICATION_PORT_OFFSET;
        int clientPort2 = repPort2 - ServerConfig.REPLICATION_PORT_OFFSET;

        List<ServerConfig.ServerAddress> serverAddresses = List.of(
                new ServerConfig.ServerAddress("127.0.0.1", clientPort0),
                new ServerConfig.ServerAddress("127.0.0.1", clientPort1),
                new ServerConfig.ServerAddress("127.0.0.1", clientPort2)
        );

        ServerConfig config1 = ServerConfig.of(serverAddresses, 1);

        // Chuan bi cac key tuong ung voi cac phan vung
        String key1Update = findKeyForServer(1, n, "user:1:profile");
        String key1Delete = findKeyForServer(1, n, "user:1:settings");
        String key1New    = findKeyForServer(1, n, "user:1:orders");
        String key0Data   = findKeyForServer(0, n, "item:0:doc");
        String key2Data   = findKeyForServer(2, n, "data:2:cache");

        assertEquals(1, HashUtil.computeServerIndex(key1Update, n));
        assertEquals(1, HashUtil.computeServerIndex(key1Delete, n));
        assertEquals(1, HashUtil.computeServerIndex(key1New, n));
        assertEquals(0, HashUtil.computeServerIndex(key0Data, n));
        assertEquals(2, HashUtil.computeServerIndex(key2Data, n));

        // ------------------------------------------------------------------
        // BUOC 1: Trang thai binh thuong
        // ------------------------------------------------------------------
        long ts1Update = store1.put(key1Update, "profile_v1");
        handler1.replicatePut(key1Update, "profile_v1", ts1Update);

        long ts1Delete = store1.put(key1Delete, "settings_v1");
        handler1.replicatePut(key1Delete, "settings_v1", ts1Delete);

        long ts0 = store0.put(key0Data, "doc_v0");
        handler0.replicatePut(key0Data, "doc_v0", ts0);

        // Cho replication dong bo sang cac node backup
        assertTrue(waitFor(() -> "profile_v1".equals(store2.get(key1Update)), 3000));
        assertTrue(waitFor(() -> "settings_v1".equals(store2.get(key1Delete)), 3000));
        assertTrue(waitFor(() -> "doc_v0".equals(store1.get(key0Data)), 3000));

        assertEquals(3, store1.size());

        // ------------------------------------------------------------------
        // BUOC 2: Gia lap su co - Kill server #1
        // ------------------------------------------------------------------
        handler1.close();
        store1.close();
        store1 = new KeyValueStore(60, 1000); // Kho RAM moi tinh trong rong
        assertEquals(0, store1.size());
        assertNull(store1.get(key1Update));

        // ------------------------------------------------------------------
        // BUOC 3: Thao tac khi Server #1 chet (Client failover sang Server #2)
        // ------------------------------------------------------------------
        // Update key1Update tren Server 2 voi timestamp moi hon
        long ts1UpdateV2 = store2.nextTimestamp();
        assertTrue(ts1UpdateV2 > ts1Update);
        store2.put(key1Update, "profile_v2_updated");

        // Them key moi key1New vao phan vung cua S1 thong qua S2
        long ts1New = store2.nextTimestamp();
        store2.put(key1New, "orders_v2_new");

        // Xoa key1Delete tren Server 2
        long ts1Del = store2.nextTimestamp();
        store2.delete(key1Delete, ts1Del);

        // Ghi key2Data chi thuoc ve Server 2 (owner = 2, backup la S0)
        long ts2 = store2.nextTimestamp();
        store2.put(key2Data, "cache_s2_private");

        // ------------------------------------------------------------------
        // BUOC 4: Bat lai Server #1 va goi recover(config1)
        // ------------------------------------------------------------------
        handler1 = new ReplicationHandler(store1);
        handler1.startListener(repPort1);
        handler1.setBackup("127.0.0.1", repPort2);

        int recoveredCount = handler1.recover(config1);
        assertTrue(recoveredCount >= 3, "Phai recover duoc it nhat 3 key (updated, new, backup)");

        // ------------------------------------------------------------------
        // BUOC 5: Kiem chung du lieu 100%
        // ------------------------------------------------------------------
        // 1. Key duoc update o S2 luc S1 chet phai mang gia tri moi nhat
        String valUpdate = store1.get(key1Update);
        assertEquals("profile_v2_updated", valUpdate);

        // 2. Key moi duoc them o S2 luc S1 chet phai co mat tren S1
        String valNew = store1.get(key1New);
        assertEquals("orders_v2_new", valNew);

        // 3. Key bi xoa o S2 luc S1 chet phai khong ton tai (null) tren S1
        String valDeleted = store1.get(key1Delete);
        assertNull(valDeleted);

        // 4. Key cua S0 ma S1 lam backup phai duoc phuc hoi ve S1 tu S0
        String valBackup = store1.get(key0Data);
        assertEquals("doc_v0", valBackup);

        // 5. Key cua S2 (owner = 2, khong thuoc phan vung cua S1) KHONG duoc phep nạp vao S1
        String valS2 = store1.get(key2Data);
        assertNull(valS2);

        // Tong so key tren Server 1 phai dung bang 3
        assertEquals(3, store1.size(), "Server 1 chi duoc phep chua dung 3 key hop le");
    }

    @Test
    void testRecoverDeleteKhongLamKeySongLai() throws Exception {
        int n = 3;

        store0 = new KeyValueStore(60, 1000);
        store1 = new KeyValueStore(60, 1000);
        store2 = new KeyValueStore(60, 1000);

        int repPort0;
        int repPort1;
        int repPort2;
        try (ServerSocket s0 = new ServerSocket(0);
             ServerSocket s1 = new ServerSocket(0);
             ServerSocket s2 = new ServerSocket(0)) {
            repPort0 = s0.getLocalPort();
            repPort1 = s1.getLocalPort();
            repPort2 = s2.getLocalPort();
        }

        int clientPort0 = repPort0 - ServerConfig.REPLICATION_PORT_OFFSET;
        int clientPort1 = repPort1 - ServerConfig.REPLICATION_PORT_OFFSET;
        int clientPort2 = repPort2 - ServerConfig.REPLICATION_PORT_OFFSET;

        List<ServerConfig.ServerAddress> serverAddresses = List.of(
                new ServerConfig.ServerAddress("127.0.0.1", clientPort0),
                new ServerConfig.ServerAddress("127.0.0.1", clientPort1),
                new ServerConfig.ServerAddress("127.0.0.1", clientPort2)
        );

        ServerConfig config0 = ServerConfig.of(serverAddresses, 0);
        ServerConfig config1 = ServerConfig.of(serverAddresses, 1);
        ServerConfig config2 = ServerConfig.of(serverAddresses, 2);

        handler0 = new ReplicationHandler(store0, config0);
        handler1 = new ReplicationHandler(store1, config1);
        handler2 = new ReplicationHandler(store2, config2);

        handler0.startListener(repPort0);
        handler1.startListener(repPort1);
        handler2.startListener(repPort2);

        handler0.setBackup("127.0.0.1", repPort1);
        handler1.setBackup("127.0.0.1", repPort2);
        handler2.setBackup("127.0.0.1", repPort0);

        // Key0 thuoc primary Server #0, replica la Server #1
        String key0 = findKeyForServer(0, n, "user_failover");
        assertEquals(0, HashUtil.computeServerIndex(key0, n));

        // 1. Client ghi vao server du phong S1 (failover path)
        long tsPut = store1.put(key0, "val_failover");
        handler1.replicatePut(key0, "val_failover", tsPut);

        // Gia tri phai duoc replicate ve primary S0
        assertTrue(waitFor(() -> "val_failover".equals(store0.get(key0)), 3000));
        assertEquals("val_failover", store1.get(key0));

        // 2. Lan recover 1 tren Primary S0
        handler0.recover(config0);
        assertEquals("val_failover", store0.get(key0));

        // 3. Giua 2 lan recover: XOA key0 tren replica S1 (tao tombstone tren S1)
        long tsDel = store1.nextTimestamp();
        store1.delete(key0, tsDel);
        assertNull(store1.get(key0));
        assertEquals(tsDel, store1.getTombstoneTimestamp(key0));

        // 4. Lan recover 2 tren Primary S0: SYNC tu S1 phai mang theo REPLICATE|DELETE tombstone
        handler0.recover(config0);

        // 5. Assert: key0 tren Primary S0 KHONG duoc phep song lai (phai la null)
        assertNull(store0.get(key0), "Key da xoa tren replica khong duoc phep song lai tren primary sau recover");
        assertEquals(tsDel, store0.getTombstoneTimestamp(key0), "Tombstone timestamp phai duoc ap vao store0");

        // Ghi de voi timestamp cu hon tombstone phai bi chan
        boolean putOlder = store0.putReplicated(key0, "stale_data", tsDel - 100);
        assertFalse(putOlder);
        assertNull(store0.get(key0));
    }

    @Test
    void testTtlReplicationVaRecoverHetHanDungThoiDiemOriginalWrite() throws Exception {
        int n = 3;
        AtomicLong clusterClock = new AtomicLong(100_000L); // t = 100_000, ttl = 60s -> expire = 160_000

        store0 = new KeyValueStore(60, 1000, clusterClock::get, false);
        store1 = new KeyValueStore(60, 1000, clusterClock::get, false);
        store2 = new KeyValueStore(60, 1000, clusterClock::get, false);

        handler0 = new ReplicationHandler(store0);
        handler1 = new ReplicationHandler(store1);
        handler2 = new ReplicationHandler(store2);

        handler0.startListener(0);
        handler1.startListener(0);
        handler2.startListener(0);

        int repPort0 = handler0.listenerPort();
        int repPort1 = handler1.listenerPort();
        int repPort2 = handler2.listenerPort();

        handler0.setBackup("127.0.0.1", repPort1);
        handler1.setBackup("127.0.0.1", repPort2);
        handler2.setBackup("127.0.0.1", repPort0);

        int clientPort0 = repPort0 - ServerConfig.REPLICATION_PORT_OFFSET;
        int clientPort1 = repPort1 - ServerConfig.REPLICATION_PORT_OFFSET;
        int clientPort2 = repPort2 - ServerConfig.REPLICATION_PORT_OFFSET;

        List<ServerConfig.ServerAddress> serverAddresses = List.of(
                new ServerConfig.ServerAddress("127.0.0.1", clientPort0),
                new ServerConfig.ServerAddress("127.0.0.1", clientPort1),
                new ServerConfig.ServerAddress("127.0.0.1", clientPort2)
        );

        ServerConfig config0 = ServerConfig.of(serverAddresses, 0);

        // Key0 co primary la Server 0, replica la Server 1
        String key0 = findKeyForServer(0, n, "ttl_test_key");
        assertEquals(0, HashUtil.computeServerIndex(key0, n));

        // 1. Ghi key0 tai thoi diem t = 100_000 tren Server 0 (primary)
        long tsWrite = store0.put(key0, "ttl_val");
        assertEquals(100_000L, tsWrite);
        handler0.replicatePut(key0, "ttl_val", tsWrite);

        // Cho replica tren Server 1 nhan du lieu
        assertTrue(waitFor(() -> "ttl_val".equals(store1.get(key0)), 3000));
        assertEquals("ttl_val", store0.get(key0));

        // 2. Tua thoi gian toi t + 50s (150_000): Server 0 bi crash
        clusterClock.set(150_000L);
        handler0.close();
        store0.close();

        // 3. Tai t + 55s (155_000): Server 0 khoi dong lai va recover tu replica Server 1
        clusterClock.set(155_000L);
        store0 = new KeyValueStore(60, 1000, clusterClock::get, false);
        handler0 = new ReplicationHandler(store0);
        handler0.startListener(repPort0);
        handler0.setBackup("127.0.0.1", repPort1);

        int recoveredCount = handler0.recover(config0);
        assertEquals(1, recoveredCount, "Key0 phai duoc recover thanh cong vi chua het han (155s < 160s)");
        assertEquals("ttl_val", store0.get(key0));
        assertEquals("ttl_val", store1.get(key0));

        // 4. Tai t + 59s (159_000): key0 van con tren ca server chinh va replica
        clusterClock.set(159_000L);
        assertEquals("ttl_val", store0.get(key0), "Key van ton tai tren primary o t+59s");
        assertEquals("ttl_val", store1.get(key0), "Key van ton tai tren replica o t+59s");

        // 5. Tai t + 60s (160_000): key0 phai HET HAN tren ca 2 server (tinh tu write timestamp goc, khong phai 155s + 60s = 215s)
        clusterClock.set(160_000L);
        assertNull(store0.get(key0), "Key0 phai het han o t+60s tren server chinh du vua recover o t+55s");
        assertNull(store1.get(key0), "Key0 phai het han o t+60s tren replica");

        // 6. Neu mot server recover sau khi key da qua han (t = 165_000):
        // snapshot tren cac node doi tac se khong con chua key het han, recover an toan voi 0 key
        clusterClock.set(165_000L);
        KeyValueStore lateStore = new KeyValueStore(60, 1000, clusterClock::get, false);
        try (ReplicationHandler lateHandler = new ReplicationHandler(lateStore)) {
            int lateRecovered = lateHandler.recover(config0);
            assertEquals(0, lateRecovered, "Ban ghi da qua han TTL khong duoc nap khi recover");
            assertNull(lateStore.get(key0), "Key qua han khong duoc xuat hien trong kho moi");
        } finally {
            lateStore.close();
        }

        // 7. Neu ban tin replication den tre sau khi key da het han (do do tre mang) -> phai bi drop
        assertFalse(store1.putReplicated("delayed_key", "delayed_val", 100_000L),
                "Ban tin replication den tre sau khi da het han phai bi bo");
        assertNull(store1.get("delayed_key"));
        assertEquals(1, store1.droppedExpiredReplicasCount(),
                "Metric droppedExpiredReplicas phai ghi nhan ban sao het han bi bo");
    }
}
