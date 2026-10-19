package com.nhom05.cache.client;

import com.nhom05.cache.common.ServerConfig.ServerAddress;
import com.nhom05.cache.server.CacheServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho CacheClient (#6).
 *
 * Bat server that voi port 0 (he dieu hanh tu chon) de khong trung cong khai.
 * Kiem tra: PUT/GET/DELETE, tieng Viet (UTF-8), validate phia client.
 */
class CacheClientTest {

    private CacheServer server;
    private CacheClient client;

    @BeforeEach
    void setUp() throws InterruptedException {
        // Bat server that, port 0 -> he dieu hanh tu cap
        server = new CacheServer(0, 60, 1000);
        Thread t = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        assertTrue(server.awaitListening(3000), "Server khong khoi dong duoc");

        // Client tro vao dung port server vua cap
        int port = server.localPort();
        client = new CacheClient(List.of(new ServerAddress("127.0.0.1", port)));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    // ------------------------------------------------------------------ PUT

    @Test
    void put_validKeyValue_returnsOk() {
        assertEquals("OK", client.put("username", "Hung"));
    }

    @Test
    void put_overwriteKey_returnsOk() {
        client.put("color", "red");
        assertEquals("OK", client.put("color", "blue"));
    }

    @Test
    void put_emptyValue_returnsOk() {
        // Value rong hop le theo giao thuc
        assertEquals("OK", client.put("emptyval", ""));
    }

    // ------------------------------------------------------------------ GET

    @Test
    void get_existingKey_returnsValue() {
        client.put("name", "Duy");
        assertEquals("VALUE|Duy", client.get("name"));
    }

    @Test
    void get_missingKey_returnsNotFound() {
        assertEquals("NOTFOUND", client.get("khong-ton-tai"));
    }

    // ------------------------------------------------------------------ DELETE

    @Test
    void delete_existingKey_returnsOk() {
        client.put("session", "abc123");
        assertEquals("OK", client.delete("session"));
    }

    @Test
    void delete_missingKey_returnsNotFound() {
        assertEquals("NOTFOUND", client.delete("key-khong-co"));
    }

    @Test
    void delete_thenGet_returnsNotFound() {
        client.put("temp", "data");
        client.delete("temp");
        assertEquals("NOTFOUND", client.get("temp"));
    }

    // ------------------------------------------------------------------ UTF-8 tieng Viet

    @Test
    void put_vietnameseName_roundTrip() {
        String value = "Ngô Mạnh Hùng";
        client.put("hoten", value);
        assertEquals("VALUE|" + value, client.get("hoten"));
    }

    @Test
    void put_vietnameseKeyAndValue_roundTrip() {
        // Key tieng Viet (khong dau "|" hoac xuong dong) la hop le
        String key = "tenhang";
        String value = "Đặc sản miền Trung — bánh tráng cuốn thịt heo";
        client.put(key, value);
        assertEquals("VALUE|" + value, client.get(key));
    }

    // ------------------------------------------------------------------ Validate phia client (khong len mang)

    @Test
    void put_keyTooLong_blockedClientSide() {
        // Key dai hon 256 ky tu bi chan ngay tai client, server khong biet gi
        String longKey = "k".repeat(257);
        String response = client.put(longKey, "value");
        assertTrue(response.startsWith("ERROR|E002"), "Phai la E002, nhan: " + response);
    }

    @Test
    void put_valueTooLong_blockedClientSide() {
        // Value dai hon 4096 ky tu bi chan tai client
        String longValue = "v".repeat(4097);
        String response = client.put("key", longValue);
        assertTrue(response.startsWith("ERROR|E003"), "Phai la E003, nhan: " + response);
    }

    @Test
    void get_keyTooLong_blockedClientSide() {
        String longKey = "x".repeat(257);
        String response = client.get(longKey);
        assertTrue(response.startsWith("ERROR|E002"), "Phai la E002, nhan: " + response);
    }

    @Test
    void delete_keyTooLong_blockedClientSide() {
        String longKey = "d".repeat(300);
        String response = client.delete(longKey);
        assertTrue(response.startsWith("ERROR|E002"), "Phai la E002, nhan: " + response);
    }

    @Test
    void put_emptyKey_blockedClientSide() {
        String response = client.put("", "value");
        assertTrue(response.startsWith("ERROR|E002"), "Phai la E002, nhan: " + response);
    }

    @Test
    void put_keyWithPipe_blockedClientSide() {
        // Ky tu | trong key lam sai giao thuc -> phai bi chan
        String response = client.put("bad|key", "value");
        assertTrue(response.startsWith("ERROR|E002"), "Phai la E002, nhan: " + response);
    }

    @Test
    void put_valueWithPipe_reportsRealReason() {
        // Value ngan nhung chua | -> khong duoc bao "Value exceeds max length"
        assertEquals("ERROR|E003|Value contains '|' or newline", client.put("k", "a|b"));
    }

    // ------------------------------------------------------------------ Response record

    @Test
    void send_returnsCorrectServedByIndex() {
        // Cluster 1 server -> moi key deu vao server index 0
        CacheClient.Response resp = client.send("anykey", "GET|anykey");
        assertEquals(0, resp.servedBy());
        assertFalse(resp.failedOver()); // Issue #6 khong failover
    }

    // ------------------------------------------------------------------ router()

    @Test
    void router_returnsHashRouter() {
        assertNotNull(client.router());
        assertEquals(1, client.router().serverCount());
    }

    // ------------------------------------------------------------------ nhieu lenh lien tiep

    @Test
    void multipleOperations_sequentially() {
        client.put("a", "1");
        client.put("b", "2");
        client.put("c", "3");
        assertEquals("VALUE|1", client.get("a"));
        assertEquals("VALUE|2", client.get("b"));
        assertEquals("VALUE|3", client.get("c"));
        client.delete("b");
        assertEquals("NOTFOUND", client.get("b"));
    }
    // ------------------------------------------------------------------ Cluster nhieu server

    @Test
    void cluster3Servers_keyGoesToCorrectPrimary() throws InterruptedException {
        // Bat 3 server that, moi server dung port 0 rieng
        CacheServer s0 = startServer();
        CacheServer s1 = startServer();
        CacheServer s2 = startServer();
        try {
            List<ServerAddress> addrs = List.of(
                    new ServerAddress("127.0.0.1", s0.localPort()),
                    new ServerAddress("127.0.0.1", s1.localPort()),
                    new ServerAddress("127.0.0.1", s2.localPort())
            );
            CacheClient c = new CacheClient(addrs);

            // Voi moi key, tinh thu cong primaryIndex roi kiem tra PUT/GET cung tra dung
            String[] keys = {"alpha", "beta", "gamma", "delta", "epsilon"};
            for (String key : keys) {
                assertEquals("OK", c.put(key, "val-" + key),
                        "PUT that bai voi key: " + key);
                assertEquals("VALUE|val-" + key, c.get(key),
                        "GET sai voi key: " + key);
            }
        } finally {
            s0.shutdown();
            s1.shutdown();
            s2.shutdown();
        }
    }

    @Test
    void cluster2Servers_servedByMatchesPrimaryIndex() throws InterruptedException {
        // Kiem tra servedBy trong Response dung voi chi so server chinh
        CacheServer s0 = startServer();
        CacheServer s1 = startServer();
        try {
            List<ServerAddress> addrs = List.of(
                    new ServerAddress("127.0.0.1", s0.localPort()),
                    new ServerAddress("127.0.0.1", s1.localPort())
            );
            CacheClient c = new CacheClient(addrs);
            HashRouter router = c.router();

            String[] keys = {"x", "y", "z", "session", "token"};
            for (String key : keys) {
                CacheClient.Response resp = c.send(key, "GET|" + key);
                int expected = router.primaryIndex(key);
                assertEquals(expected, resp.servedBy(),
                        "servedBy sai voi key '" + key + "': expected=" + expected);
                assertFalse(resp.failedOver());
            }
        } finally {
            s0.shutdown();
            s1.shutdown();
        }
    }

    // ------------------------------------------------------------------ Server tat

    @Test
    void serverDown_returnsE004() {
        // Tro client vao port khong co server -> phai tra ERROR|E004
        CacheClient deadClient = new CacheClient(
                List.of(new ServerAddress("127.0.0.1", 1)), // port 1 luon bi tu choi
                200 // timeout ngan de test nhanh
        );
        String response = deadClient.get("anykey");
        assertTrue(response.startsWith("ERROR|E004"),
                "Server tat phai tra E004, nhan: " + response);
    }

    @Test
    void serverShutdownAfterStart_returnsE004() throws InterruptedException {
        // Bat server, ghi 1 key thanh cong, roi tat server, kiem tra loi
        CacheServer s = startServer();
        CacheClient c = new CacheClient(
                List.of(new ServerAddress("127.0.0.1", s.localPort())),
                500
        );
        assertEquals("OK", c.put("k", "v"), "PUT truoc khi tat server phai OK");
        s.shutdown();
        String response = c.get("k");
        assertTrue(response.startsWith("ERROR|E004"),
                "Server tat phai tra E004, nhan: " + response);
    }

    @Test
    void singleServerDown_noFailoverToItself() {
        // Cum 1 server: server du phong chinh la no -> khong gui lai lan 2
        CacheClient one = new CacheClient(List.of(new ServerAddress("127.0.0.1", 1)), 200);
        CacheClient.Response r = one.send("k", "GET|k");
        assertTrue(r.line().startsWith("ERROR|E004"), r.line());
        assertFalse(r.failedOver(), "khong co server du phong thi khong duoc bao la da failover");
        assertEquals(0, r.servedBy());
    }

    // ------------------------------------------------------------------ Failover (#7)

    @Test
    void failover_primaryDown_requestSuccessViaReplica() throws InterruptedException {
        // Bat 2 server that
        CacheServer s0 = startServer();
        CacheServer s1 = startServer();
        try {
            List<ServerAddress> addrs = List.of(
                    new ServerAddress("127.0.0.1", s0.localPort()),
                    new ServerAddress("127.0.0.1", s1.localPort())
            );
            CacheClient c = new CacheClient(addrs, 500);

            // Chon key co primaryIndex = 0
            // Tim key nao co primary = 0
            String key = "failover-key";
            int primaryIdx = c.router().primaryIndex(key);
            int replicaIdx = c.router().replicaIndex(primaryIdx);

            // Ghi du lieu vao server du phong (gia lap replication)
            CacheServer[] physServers = {s0, s1};
            CacheClient onlyReplica = new CacheClient(
                    List.of(addrs.get(replicaIdx)), 500);
            assertEquals("OK", onlyReplica.put(key, "hello-failover"));

            // Tat server chinh
            physServers[primaryIdx].shutdown();
            Thread.sleep(200);

            // Request phai thanh cong qua server du phong
            CacheClient.Response resp = c.send(key, "GET|" + key);
            assertTrue(resp.failedOver(), "Phai failedOver() == true");
            assertEquals("VALUE|hello-failover", resp.line(),
                    "Phai lay duoc gia tri tu server du phong");
        } finally {
            s0.shutdown();
            s1.shutdown();
        }
    }

    @Test
    void failover_e002_noFailover() throws InterruptedException {
        // E002 la loi du lieu -> khong failover, tra luon
        CacheServer s0 = startServer();
        CacheServer s1 = startServer();
        try {
            List<ServerAddress> addrs = List.of(
                    new ServerAddress("127.0.0.1", s0.localPort()),
                    new ServerAddress("127.0.0.1", s1.localPort())
            );
            CacheClient c = new CacheClient(addrs, 500);

            // Key rong -> E002, khong failover (du ca 2 server dang chay)
            CacheClient.Response resp = c.send("", "GET|");
            assertTrue(resp.line().startsWith("ERROR|E002"),
                    "E002 phai tra luon, nhan: " + resp.line());
            assertFalse(resp.failedOver(), "E002 khong duoc failover");
        } finally {
            s0.shutdown();
            s1.shutdown();
        }
    }

    @Test
    void failover_put_primaryDown_successViaReplica() throws InterruptedException {
        // PUT cung failover khi server chinh tat
        CacheServer s0 = startServer();
        CacheServer s1 = startServer();
        try {
            List<ServerAddress> addrs = List.of(
                    new ServerAddress("127.0.0.1", s0.localPort()),
                    new ServerAddress("127.0.0.1", s1.localPort())
            );
            CacheClient c = new CacheClient(addrs, 500);

            String key = "put-failover-key";
            int primaryIdx = c.router().primaryIndex(key);
            CacheServer[] physServers = {s0, s1};
            physServers[primaryIdx].shutdown();
            Thread.sleep(200);

            CacheClient.Response resp = c.send(key, "PUT|" + key + "|hello");
            assertTrue(resp.failedOver(), "PUT phai failedOver() == true khi server chinh tat");
            assertEquals("OK", resp.line(), "PUT qua server du phong phai tra OK");
        } finally {
            s0.shutdown();
            s1.shutdown();
        }
    }

    @Test
    void failover_bothServersDown_returnsE004() throws InterruptedException {
        // Bat 2 server that roi tat ca 2
        CacheServer s0 = startServer();
        CacheServer s1 = startServer();

        List<ServerAddress> addrs = List.of(
                new ServerAddress("127.0.0.1", s0.localPort()),
                new ServerAddress("127.0.0.1", s1.localPort())
        );
        CacheClient c = new CacheClient(addrs, 500);

        // Tat ca 2 server
        s0.shutdown();
        s1.shutdown();
        Thread.sleep(200);

        // Phai tra ERROR|E004
        String response = c.get("any-key");
        assertTrue(response.startsWith("ERROR|E004"),
                "Ca 2 server tat phai tra ERROR|E004, nhan: " + response);
    }

    // ------------------------------------------------------------------ helper

    /** Bat 1 CacheServer that voi port 0, doi den khi san sang. */
    private CacheServer startServer() throws InterruptedException {
        CacheServer s = new CacheServer(0, 60, 1000);
        Thread t = new Thread(() -> {
            try {
                s.start();
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        assertTrue(s.awaitListening(3000), "Server khong khoi dong duoc");
        return s;
    }
}
