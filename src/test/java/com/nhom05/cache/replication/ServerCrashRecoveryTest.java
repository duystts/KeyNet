package com.nhom05.cache.replication;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.server.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
            if (HashUtil.selectServer(key, n) == targetServer) {
                return key;
            }
            seq++;
        }
    }

    @Test
    void testServerChetRoiPhucHoiDuLieuThanhCong() throws Exception {
        System.out.println("================================================================================");
        System.out.println("KICH BAN GIA LAP: 1 SERVER CHET ROI PHUC HOI DU LIEU QUA SYNC (ISSUE #10)");
        System.out.println("Mo hinh cum 3 Cache Server (N = 3):");
        System.out.println("  - Server 0: primary partition 0, backup partition 2");
        System.out.println("  - Server 1: primary partition 1, backup partition 0");
        System.out.println("  - Server 2: primary partition 2, backup partition 1");
        System.out.println("================================================================================");

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

        assertEquals(1, HashUtil.selectServer(key1Update, n));
        assertEquals(1, HashUtil.selectServer(key1Delete, n));
        assertEquals(1, HashUtil.selectServer(key1New, n));
        assertEquals(0, HashUtil.selectServer(key0Data, n));
        assertEquals(2, HashUtil.selectServer(key2Data, n));

        // ------------------------------------------------------------------
        // BUOC 1: Trang thai binh thuong
        // ------------------------------------------------------------------
        System.out.println("\n[BUOC 1] TRANG THAI BINH THUONG:");
        long ts1Update = store1.put(key1Update, "profile_v1");
        handler1.replicatePut(key1Update, "profile_v1", ts1Update);
        System.out.println("  + Ghi key '" + key1Update + "' vao S1 [ts=" + ts1Update + "], replicate sang S2");

        long ts1Delete = store1.put(key1Delete, "settings_v1");
        handler1.replicatePut(key1Delete, "settings_v1", ts1Delete);
        System.out.println("  + Ghi key '" + key1Delete + "' vao S1 [ts=" + ts1Delete + "], replicate sang S2");

        long ts0 = store0.put(key0Data, "doc_v0");
        handler0.replicatePut(key0Data, "doc_v0", ts0);
        System.out.println("  + Ghi key '" + key0Data + "' vao S0 [ts=" + ts0 + "], replicate sang S1 (S1 lam backup)");

        // Cho replication dong bo sang cac node backup
        assertTrue(waitFor(() -> "profile_v1".equals(store2.get(key1Update)), 3000));
        assertTrue(waitFor(() -> "settings_v1".equals(store2.get(key1Delete)), 3000));
        assertTrue(waitFor(() -> "doc_v0".equals(store1.get(key0Data)), 3000));

        System.out.println("  => Ket qua Buoc 1: Server 1 dang luu 3 key (2 key chinh + 1 key backup).");
        assertEquals(3, store1.size());

        // ------------------------------------------------------------------
        // BUOC 2: Gia lap su co - Kill server #1
        // ------------------------------------------------------------------
        System.out.println("\n[BUOC 2] GIA LAP SU CO - KILL SERVER #1:");
        handler1.close();
        store1.close();
        store1 = new KeyValueStore(60, 1000); // Kho RAM moi tinh trong rong
        System.out.println("  + Dong ReplicationHandler cua Server 1.");
        System.out.println("  + Xoa sach kho RAM cua Server 1 (size = " + store1.size() + ").");
        assertEquals(0, store1.size());
        assertNull(store1.get(key1Update));
        System.out.println("  => Server 1 da chet hoan toan.");

        // ------------------------------------------------------------------
        // BUOC 3: Thao tac khi Server #1 chet (Client failover sang Server #2)
        // ------------------------------------------------------------------
        System.out.println("\n[BUOC 3] CLIENT FAILOVER SANG SERVER #2 (BACKUP CUA S1):");
        // Update key1Update tren Server 2 voi timestamp moi hon
        long ts1UpdateV2 = store2.nextTimestamp();
        assertTrue(ts1UpdateV2 > ts1Update);
        store2.put(key1Update, "profile_v2_updated");
        System.out.println("  + Client UPDATE key '" + key1Update + "' tren S2 thanh 'profile_v2_updated' [ts=" + ts1UpdateV2 + "]");

        // Them key moi key1New vao phan vung cua S1 thong qua S2
        long ts1New = store2.nextTimestamp();
        store2.put(key1New, "orders_v2_new");
        System.out.println("  + Client INSERT key moi '" + key1New + "' vao S2 [ts=" + ts1New + "]");

        // Xoa key1Delete tren Server 2
        long ts1Del = store2.nextTimestamp();
        store2.delete(key1Delete, ts1Del);
        System.out.println("  + Client DELETE key '" + key1Delete + "' tren S2 [ts=" + ts1Del + "]");

        // Ghi key2Data chi thuoc ve Server 2 (owner = 2, backup la S0)
        long ts2 = store2.nextTimestamp();
        store2.put(key2Data, "cache_s2_private");
        System.out.println("  + Ghi key rieng cua S2 '" + key2Data + "' [owner=2, ts=" + ts2 + "]");

        // ------------------------------------------------------------------
        // BUOC 4: Bat lai Server #1 va goi recover(config1)
        // ------------------------------------------------------------------
        System.out.println("\n[BUOC 4] BAT LAI SERVER #1 VA TIEN HANH PHUC HOI (RECOVER):");
        handler1 = new ReplicationHandler(store1);
        handler1.startListener(repPort1);
        handler1.setBackup("127.0.0.1", repPort2);

        System.out.println("  + Server 1 bat lai listener replication tai cong " + repPort1);
        System.out.println("  + Goi handler1.recover(config1) de dong bo tu S2 (next) va S0 (prev)...");

        int recoveredCount = handler1.recover(config1);
        System.out.println("  => Tong so key duoc recover nap vao Server 1: " + recoveredCount);
        assertTrue(recoveredCount >= 3, "Phai recover duoc it nhat 3 key (updated, new, backup)");

        // ------------------------------------------------------------------
        // BUOC 5: Kiem chung du lieu 100%
        // ------------------------------------------------------------------
        System.out.println("\n[BUOC 5] KIEM CHUNG KET QUA PHUC HOI TREN SERVER #1:");

        // 1. Key duoc update o S2 luc S1 chet phai mang gia tri moi nhat
        String valUpdate = store1.get(key1Update);
        System.out.println("  [KIEM TRA 1] '" + key1Update + "': ky vong='profile_v2_updated', thuc te='" + valUpdate + "'");
        assertEquals("profile_v2_updated", valUpdate);

        // 2. Key moi duoc them o S2 luc S1 chet phai co mat tren S1
        String valNew = store1.get(key1New);
        System.out.println("  [KIEM TRA 2] '" + key1New + "': ky vong='orders_v2_new', thuc te='" + valNew + "'");
        assertEquals("orders_v2_new", valNew);

        // 3. Key bi xoa o S2 luc S1 chet phai khong ton tai (null) tren S1
        String valDeleted = store1.get(key1Delete);
        System.out.println("  [KIEM TRA 3] '" + key1Delete + "': ky vong=null, thuc te=" + valDeleted);
        assertNull(valDeleted);

        // 4. Key cua S0 ma S1 lam backup phai duoc phuc hoi ve S1 tu S0
        String valBackup = store1.get(key0Data);
        System.out.println("  [KIEM TRA 4] '" + key0Data + "' (backup cua S0): ky vong='doc_v0', thuc te='" + valBackup + "'");
        assertEquals("doc_v0", valBackup);

        // 5. Key cua S2 (owner = 2, khong thuoc phan vung cua S1) KHONG duoc phep nạp vao S1
        String valS2 = store1.get(key2Data);
        System.out.println("  [KIEM TRA 5] '" + key2Data + "' (owner=2, khong thuoc S1): ky vong=null, thuc te=" + valS2);
        assertNull(valS2);

        // Tong so key tren Server 1 phai dung bang 3
        assertEquals(3, store1.size(), "Server 1 chi duoc phep chua dung 3 key hop le");

        System.out.println("================================================================================");
        System.out.println("KET QUA: TEST PHUC HOI SERVER (ISSUE #10) THANH CONG 100%!");
        System.out.println("================================================================================");
    }
}
