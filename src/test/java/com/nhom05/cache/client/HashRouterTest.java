package com.nhom05.cache.client;

import com.nhom05.cache.common.HashUtil;
import com.nhom05.cache.common.ServerConfig.ServerAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho HashRouter (#5).
 *
 * Kiem tra: phan bo key, replica quay vong, key khong ra so am,
 * danh sach rong/null bao loi.
 */
class HashRouterTest {

    // Cluster 3 server: 127.0.0.1:5001, :5002, :5003
    private static final List<ServerAddress> THREE_SERVERS = List.of(
            new ServerAddress("127.0.0.1", 5001),
            new ServerAddress("127.0.0.1", 5002),
            new ServerAddress("127.0.0.1", 5003)
    );

    private HashRouter router3;

    @BeforeEach
    void setUp() {
        router3 = new HashRouter(THREE_SERVERS);
    }

    // ------------------------------------------------------------------ constructor

    @Test
    void constructor_null_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new HashRouter(null));
    }

    @Test
    void constructor_empty_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new HashRouter(Collections.emptyList()));
    }

    @Test
    void constructor_oneServer_ok() {
        HashRouter single = new HashRouter(List.of(new ServerAddress("127.0.0.1", 5001)));
        assertEquals(1, single.serverCount());
    }

    // ------------------------------------------------------------------ serverCount / servers / server

    @Test
    void serverCount_returnsCorrectSize() {
        assertEquals(3, router3.serverCount());
    }

    @Test
    void servers_returnsImmutableList() {
        List<ServerAddress> list = router3.servers();
        assertThrows(UnsupportedOperationException.class, () -> list.add(new ServerAddress("x", 1)));
    }

    @Test
    void server_validIndex_returnsCorrectAddress() {
        assertEquals(new ServerAddress("127.0.0.1", 5001), router3.server(0));
        assertEquals(new ServerAddress("127.0.0.1", 5003), router3.server(2));
    }

    @Test
    void server_negativeIndex_throwsIndexOutOfBounds() {
        assertThrows(IndexOutOfBoundsException.class, () -> router3.server(-1));
    }

    @Test
    void server_indexTooLarge_throwsIndexOutOfBounds() {
        assertThrows(IndexOutOfBoundsException.class, () -> router3.server(3));
    }

    // ------------------------------------------------------------------ primaryIndex

    @Test
    void primaryIndex_fixedKey_matchesHashUtil() {
        // Ket qua phai giong HashUtil.computeServerIndex — khong dung cong thuc rieng
        String[] keys = {"username", "session", "token", "cart", "order"};
        for (String key : keys) {
            int expected = HashUtil.computeServerIndex(key, 3);
            assertEquals(expected, router3.primaryIndex(key),
                    "primaryIndex sai voi key: " + key);
        }
    }

    @Test
    void primaryIndex_neverNegative() {
        // "polygenelubricants".hashCode() == Integer.MIN_VALUE trong Java
        // Math.abs(Integer.MIN_VALUE) van am -> cong thuc phai xu ly dung
        int idx = router3.primaryIndex("polygenelubricants");
        assertTrue(idx >= 0, "primaryIndex phai >= 0, nhan duoc: " + idx);
        assertTrue(idx < 3, "primaryIndex phai < N, nhan duoc: " + idx);
    }

    @Test
    void primaryIndex_singleServer_alwaysZero() {
        HashRouter single = new HashRouter(List.of(new ServerAddress("127.0.0.1", 5001)));
        assertEquals(0, single.primaryIndex("bat-ky-key-nao"));
        assertEquals(0, single.primaryIndex("polygenelubricants"));
    }

    // ------------------------------------------------------------------ replicaIndex

    @Test
    void replicaIndex_normalServer_returnsNext() {
        // server 0 -> replica 1, server 1 -> replica 2
        assertEquals(1, router3.replicaIndex(0));
        assertEquals(2, router3.replicaIndex(1));
    }

    @Test
    void replicaIndex_lastServer_wrapsToZero() {
        // server cuoi (index 2) -> quay vong ve 0
        assertEquals(0, router3.replicaIndex(2));
    }

    @Test
    void replicaIndex_singleServer_alwaysZero() {
        // cluster 1 server: replica cua 0 van la 0
        HashRouter single = new HashRouter(List.of(new ServerAddress("127.0.0.1", 5001)));
        assertEquals(0, single.replicaIndex(0));
    }

    @Test
    void replicaIndex_invalidPrimary_throwsIndexOutOfBounds() {
        assertThrows(IndexOutOfBoundsException.class, () -> router3.replicaIndex(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> router3.replicaIndex(3));
    }

    @Test
    void replicaIndex_matchesHashUtil() {
        for (int i = 0; i < 3; i++) {
            assertEquals(HashUtil.replicaIndex(i, 3), router3.replicaIndex(i));
        }
    }

    // ------------------------------------------------------------------ phan bo key

    @Test
    void primaryIndex_distributeKeys_allServersReachable() {
        // Voi tap key du lon, moi server phai nhan duoc it nhat 1 key
        boolean[] reached = new boolean[3];
        String[] keys = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j",
            "k", "l", "m", "n", "o", "p", "q", "r", "s", "t"
        };
        for (String key : keys) {
            reached[router3.primaryIndex(key)] = true;
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(reached[i], "Server " + i + " khong nhan duoc key nao");
        }
    }
}
