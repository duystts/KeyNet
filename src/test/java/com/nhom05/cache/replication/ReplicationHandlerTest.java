package com.nhom05.cache.replication;

import com.nhom05.cache.server.CacheServer;
import com.nhom05.cache.server.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Test Module 3: giai quyet xung dot (unit) + dong bo that giua 2 server (tich hop). */
@SuppressWarnings("resource")
class ReplicationHandlerTest {

    private static final int PORT_A = 6101, REPL_A = 7101;
    private static final int PORT_B = 6102, REPL_B = 7102;

    private KeyValueStore unitStore;
    private ReplicationHandler unitHandler;

    private CacheServer serverA, serverB;
    private ReplicationHandler replA, replB;

    @BeforeEach
    void setUp() {
        unitStore = new KeyValueStore(60, 100);
        unitHandler = new ReplicationHandler(unitStore);
    }

    @AfterEach
    void tearDown() {
        unitHandler.close();
        unitStore.close();
        if (serverA != null) serverA.shutdown();
        if (serverB != null) serverB.shutdown();
        if (replA != null) replA.close();
        if (replB != null) replB.close();
    }

    // ---------------------------------------------------------------- unit: conflict resolution

    @Test
    void newerTimestampWinsEvenWhenArrivesOutOfOrder() {
        assertEquals("OK", unitHandler.apply("REPLICATE|PUT|user:1|v1000|1000"));
        // Lenh ts=3000 den truoc lenh ts=2000
        assertEquals("OK", unitHandler.apply("REPLICATE|PUT|user:1|v3000|3000"));
        assertEquals("OK", unitHandler.apply("REPLICATE|PUT|user:1|v2000|2000"));
        assertEquals("OK", unitHandler.apply("REPLICATE|PUT|user:1|v500|500"));
        assertEquals("v3000", unitStore.get("user:1"));
    }

    @Test
    void deleteWithNewerTimestampRemovesKey() {
        unitHandler.apply("REPLICATE|PUT|k|v|1000");
        assertEquals("OK", unitHandler.apply("REPLICATE|DELETE|k|2000"));
        assertNull(unitStore.get("k"));
    }

    @Test
    void deleteWithOlderTimestampIsIgnored() {
        unitHandler.apply("REPLICATE|PUT|k|v|2000");
        unitHandler.apply("REPLICATE|DELETE|k|1000");
        assertEquals("v", unitStore.get("k"));
    }

    @Test
    void lateOldPutDoesNotResurrectDeletedKey() {
        unitHandler.apply("REPLICATE|PUT|k|v|1000");
        unitHandler.apply("REPLICATE|DELETE|k|3000");
        unitHandler.apply("REPLICATE|PUT|k|stale|2000"); // den tre, cu hon lan xoa
        assertNull(unitStore.get("k"));
        unitHandler.apply("REPLICATE|PUT|k|fresh|4000"); // ghi moi hon thi hop le
        assertEquals("fresh", unitStore.get("k"));
    }

    @Test
    void vietnameseValueSurvives() {
        unitHandler.apply("REPLICATE|PUT|ten|Nguyễn Tiến Đức|1000");
        assertEquals("Nguyễn Tiến Đức", unitStore.get("ten"));
    }

    @Test
    void malformedMessagesReturnE001AndChangeNothing() {
        String[] bad = {
                null, "", "HELLO", "REPLICATE", "REPLICATE|PUT|k|v", // thieu timestamp
                "REPLICATE|PUT|k|v|abc",                             // timestamp khong phai so
                "REPLICATE|PUT|k|a|b|1000",                          // value chua '|'
                "REPLICATE|DELETE|k", "REPLICATE|FOO|k|1000", "REPLICATE|PUT||v|1000"
        };
        for (String line : bad) {
            assertTrue(unitHandler.apply(line).startsWith("ERROR|E001"), "phai tu choi: " + line);
        }
        assertEquals(0, unitStore.size());
    }

    @Test
    void replicatedEntryExpiresLikeNormalEntry() throws Exception {
        try (KeyValueStore shortLived = new KeyValueStore(1, 100)) {
            new ReplicationHandler(shortLived).apply("REPLICATE|PUT|k|v|1000");
            assertEquals("v", shortLived.get("k"));
            Thread.sleep(1300);
            assertNull(shortLived.get("k"), "ban sao khong duoc song vo han");
        }
    }

    @Test
    void replicatedWritesRespectMaxEntries() {
        try (KeyValueStore tiny = new KeyValueStore(60, 2)) {
            ReplicationHandler h = new ReplicationHandler(tiny);
            for (int i = 0; i < 10; i++) {
                h.apply("REPLICATE|PUT|k" + i + "|v|" + (1000 + i));
            }
            assertTrue(tiny.size() <= 2);
        }
    }

    // ---------------------------------------------------------------- tich hop: 2 server that

    private void startPair(boolean startB) throws Exception {
        serverA = new CacheServer(PORT_A, 60, 100);
        replA = new ReplicationHandler(serverA.store());
        replA.configureBackupServer("127.0.0.1", REPL_B);
        replA.startListener(REPL_A);
        serverA.setReplicator(replA);
        new Thread(() -> {
            try { serverA.start(); } catch (IOException ignored) { }
        }).start();

        if (startB) {
            serverB = new CacheServer(PORT_B, 60, 100);
            replB = new ReplicationHandler(serverB.store());
            replB.startListener(REPL_B);
            serverB.setReplicator(replB);
            new Thread(() -> {
                try { serverB.start(); } catch (IOException ignored) { }
            }).start();
        }
        Thread.sleep(300);
    }

    @Test
    void putAndDeleteOnPrimaryAreReplicatedToBackup() throws Exception {
        startPair(true);
        assertEquals("OK", send(PORT_A, "PUT|city|Hồ Chí Minh"));
        awaitTrue(() -> "Hồ Chí Minh".equals(serverB.store().get("city")), "PUT chua sang backup");

        assertEquals("OK", send(PORT_A, "PUT|city|Hà Nội"));
        awaitTrue(() -> "Hà Nội".equals(serverB.store().get("city")), "ghi de chua sang backup");

        assertEquals("OK", send(PORT_A, "DELETE|city"));
        awaitTrue(() -> serverB.store().get("city") == null, "DELETE chua sang backup");
    }

    @Test
    void backupDownDoesNotBreakPrimary() throws Exception {
        startPair(false);
        long start = System.nanoTime();
        assertEquals("OK", send(PORT_A, "PUT|k|v"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500,
                "replication bat dong bo: khong duoc lam cham response cua client");
        assertEquals("VALUE|v", send(PORT_A, "GET|k"));
    }

    // ---------------------------------------------------------------- helpers

    private static String send(int port, String line) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static void awaitTrue(Supplier<Boolean> cond, String msg) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) return;
            Thread.sleep(25);
        }
        fail(msg);
    }
}
