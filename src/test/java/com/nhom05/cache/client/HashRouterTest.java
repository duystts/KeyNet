package com.nhom05.cache.client;

import com.nhom05.cache.common.ServerConfig.ServerAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HashRouterTest {

    private HashRouter router;
    private List<ServerAddress> servers;

    @BeforeEach
    void setUp() {
        servers = List.of(
                new ServerAddress("127.0.0.1", 5001),
                new ServerAddress("127.0.0.1", 5002),
                new ServerAddress("127.0.0.1", 5003)
        );
        router = new HashRouter(servers);
    }

    @Test
    void testGetServerCount() {
        assertEquals(3, router.getServerCount());
    }

    @Test
    void testGetPrimaryServerIndexConsistency() {
        String key = "testKey123";
        int index1 = router.getPrimaryServerIndex(key);
        int index2 = router.getPrimaryServerIndex(key);

        assertEquals(index1, index2);
        assertTrue(index1 >= 0 && index1 < 3);
        assertEquals(servers.get(index1), router.getPrimaryServer(key));
    }

    @Test
    void testGetReplicaServerIndex() {
        assertEquals(1, router.getReplicaServerIndex(0));
        assertEquals(2, router.getReplicaServerIndex(1));
        assertEquals(0, router.getReplicaServerIndex(2));

        assertEquals(servers.get(1), router.getReplicaServer(0));
        assertEquals(servers.get(2), router.getReplicaServer(1));
        assertEquals(servers.get(0), router.getReplicaServer(2));
    }

    @Test
    void testGetReplicaServerForKey() {
        String key = "sampleKey";
        int primaryIndex = router.getPrimaryServerIndex(key);
        int expectedReplicaIndex = (primaryIndex + 1) % 3;

        assertEquals(servers.get(expectedReplicaIndex), router.getReplicaServerForKey(key));
    }

    @Test
    void testConstructorValidation() {
        assertThrows(NullPointerException.class, () -> new HashRouter(null));
        assertThrows(IllegalArgumentException.class, () -> new HashRouter(Collections.emptyList()));
    }
}
