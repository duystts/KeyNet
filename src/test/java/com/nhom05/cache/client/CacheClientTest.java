package com.nhom05.cache.client;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ServerConfig.ServerAddress;
import com.nhom05.cache.server.CacheServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CacheClientTest {

    private static final int PORT_0 = 5901;
    private static final int PORT_1 = 5902;

    private CacheServer server0;
    private CacheServer server1;
    private ExecutorService serverExecutor;
    private CacheClient client;

    @BeforeEach
    void setUp() throws Exception {
        server0 = new CacheServer(PORT_0, 60, 100);
        server1 = new CacheServer(PORT_1, 60, 100);

        serverExecutor = Executors.newFixedThreadPool(2);
        serverExecutor.execute(() -> {
            try {
                server0.start();
            } catch (IOException ignored) {
            }
        });
        serverExecutor.execute(() -> {
            try {
                server1.start();
            } catch (IOException ignored) {
            }
        });

        // Cho 100ms de socket server lăng nghe
        Thread.sleep(100);

        List<ServerAddress> servers = List.of(
                new ServerAddress("127.0.0.1", PORT_0),
                new ServerAddress("127.0.0.1", PORT_1)
        );
        client = new CacheClient(servers, 1000);
    }

    @AfterEach
    void tearDown() {
        if (server0 != null) server0.shutdown();
        if (server1 != null) server1.shutdown();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void testPutGetDeleteNormalFlow() {
        String key = "user:1001";
        String value = "NguyenVanA";

        String putResp = client.put(key, value);
        assertEquals("OK", putResp);

        String getResp = client.get(key);
        assertEquals("VALUE|" + value, getResp);

        String delResp = client.delete(key);
        assertEquals("OK", delResp);

        String getAfterDel = client.get(key);
        assertEquals("NOTFOUND", getAfterDel);
    }

    @Test
    void testFailoverWhenPrimaryServerDown() throws Exception {
        String key = "failoverKey"; // Tim key map vao Server 0 hay Server 1
        int primaryIdx = client.getRouter().getPrimaryServerIndex(key);

        // Tat server chinh cua key nay
        if (primaryIdx == 0) {
            server0.shutdown();
        } else {
            server1.shutdown();
        }
        Thread.sleep(100);

        // Khi server chinh down, client phai tu dong chuyen (failover) sang server du phong
        // Vi server du phong van dang chay (nhung chua co key nay), GET se tra ve NOTFOUND chu khong bi crash/timeout
        String getResp = client.get(key);
        assertEquals("NOTFOUND", getResp);

        // Client PUT vao key nay -> se duoc ghi thanh cong tren server du phong
        String putResp = client.put(key, "BackupValue");
        assertEquals("OK", putResp);

        String getAfterPut = client.get(key);
        assertEquals("VALUE|BackupValue", getAfterPut);
    }

    @Test
    void testBothServersDownReturnsError() throws Exception {
        server0.shutdown();
        server1.shutdown();
        Thread.sleep(100);

        String response = client.get("anyKey");
        assertTrue(response.startsWith("ERROR|" + ErrorCode.E004.code()), "Response phai tra ve loi E004 khi ca 2 server deu down");
    }

    @Test
    void testInvalidKeyOrValueLength() {
        String longKey = "a".repeat(300);
        String errKey = client.put(longKey, "val");
        assertTrue(errKey.startsWith("ERROR|" + ErrorCode.E002.code()));

        String longVal = "v".repeat(5000);
        String errVal = client.put("validKey", longVal);
        assertTrue(errVal.startsWith("ERROR|" + ErrorCode.E003.code()));
    }
}
