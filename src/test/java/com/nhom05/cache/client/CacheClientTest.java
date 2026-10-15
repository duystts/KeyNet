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
}
