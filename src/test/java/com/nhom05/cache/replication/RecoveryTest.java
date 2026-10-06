package com.nhom05.cache.replication;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.server.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module 3 - "gia lap 1 server chet roi phuc hoi" (bang phan cong): server #0 khoi dong lai
 * voi kho trong phai lay lai du lieu tu server ke ben qua SYNC, ban moi hon (timestamp) thang.
 */
@SuppressWarnings("resource")
class RecoveryTest {

    // 3 server: client port 6401..6403 -> replication port 7401..7403
    private static final int BASE = 6401;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    private ServerConfig config(int self) throws IOException {
        Path file = tmp.resolve("c" + self + ".properties");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append("server.").append(i).append(".host=127.0.0.1\n");
            sb.append("server.").append(i).append(".port=").append(BASE + i).append('\n');
        }
        sb.append("cache.serverIndex=").append(self).append('\n');
        Files.writeString(file, sb.toString());
        return ServerConfig.load(file.toString());
    }

    /** Dung 1 "server" chi gom kho + listener replication (du cho kich ban phuc hoi). */
    private KeyValueStore peer(int index) throws IOException {
        KeyValueStore store = new KeyValueStore(60, 1000);
        ReplicationHandler h = new ReplicationHandler(store);
        h.startListener(config(index).replicationPort(index));
        toClose.add(h);
        toClose.add(store);
        return store;
    }

    private static String keyOwnedBy(int server, String prefix) {
        for (int i = 0; ; i++) {
            if (HashUtil.computeServerIndex(prefix + i, 3) == server) {
                return prefix + i;
            }
        }
    }

    @Test
    void restartedServerGetsBackItsOwnAndReplicaData() throws Exception {
        KeyValueStore s1 = peer(1);
        KeyValueStore s2 = peer(2);

        String own = keyOwnedBy(0, "own");       // server chinh = #0: ban sao nam o #1
        String failover = keyOwnedBy(0, "fo");   // client ghi vao #1 trong luc #0 chet
        String replicaOf2 = keyOwnedBy(2, "r");  // server chinh = #2: #0 la du phong
        String notMine = keyOwnedBy(1, "x");     // server chinh = #1: #0 khong can giu

        s1.putReplicated(own, "v-own", 1000);
        s1.put(failover, "v-failover");
        s1.put(notMine, "v-not-mine");
        s2.put(replicaOf2, "v-r");

        try (KeyValueStore s0 = new KeyValueStore(60, 1000)) {
            int restored = new ReplicationHandler(s0).recover(config(0));
            assertEquals(3, restored);
            assertEquals("v-own", s0.get(own));
            assertEquals("v-failover", s0.get(failover));
            assertEquals("v-r", s0.get(replicaOf2));
            assertNull(s0.get(notMine), "khong keo ve key khong thuoc server #0");
        }
    }

    @Test
    void newerLocalValueWinsOverOlderSyncedValue() throws Exception {
        KeyValueStore s1 = peer(1);
        peer(2);
        String key = keyOwnedBy(0, "k");
        s1.putReplicated(key, "old", 1000);

        try (KeyValueStore s0 = new KeyValueStore(60, 1000)) {
            s0.putReplicated(key, "new", 5000);
            new ReplicationHandler(s0).recover(config(0));
            assertEquals("new", s0.get(key));
        }
    }

    @Test
    void neighboursDownMeansNothingToRestoreAndNoError() throws Exception {
        try (KeyValueStore s0 = new KeyValueStore(60, 1000)) {
            assertEquals(0, new ReplicationHandler(s0).recover(config(0)));
            assertEquals(0, s0.size());
        }
    }

    @Test
    void syncAnswersWithAllLiveEntriesThenEnd() throws Exception {
        KeyValueStore s1 = peer(1);
        s1.put("a", "Nguyễn Tiến Đức");
        s1.put("b", "2");
        s1.delete("b");
        try (KeyValueStore target = new KeyValueStore(60, 1000)) {
            int got = new ReplicationHandler(target).syncFrom("127.0.0.1", config(1).replicationPort(1), k -> true);
            assertEquals(1, got);
            assertEquals("Nguyễn Tiến Đức", target.get("a"));
        }
    }
}
