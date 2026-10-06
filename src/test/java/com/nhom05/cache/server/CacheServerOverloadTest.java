package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Server qua tai phai tra ERROR|E004 (muc 3 dac ta) de client failover, khong im lang treo. */
@SuppressWarnings("resource")
class CacheServerOverloadTest {

    private static final int PORT = 6202;
    private CacheServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.shutdown();
    }

    @Test
    void returnsE004WhenThreadsAndQueueAreFull() throws Exception {
        server = new CacheServer(PORT, 60, 100, 1, 1);
        new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        }).start();
        Thread.sleep(200);

        try (Socket busy = new Socket("127.0.0.1", PORT)) {      // chiem thread duy nhat
            Thread.sleep(100);
            try (Socket queued = new Socket("127.0.0.1", PORT)) { // nam trong hang doi (suc chua 1)
                Thread.sleep(100);
                try (Socket rejected = new Socket("127.0.0.1", PORT)) {
                    rejected.setSoTimeout(2000);
                    String reply = new BufferedReader(new InputStreamReader(
                            rejected.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    assertEquals("ERROR|E004|Server overloaded", reply);
                }
                // Ket noi dang cho van duoc phuc vu binh thuong khi thread ranh
                busy.getOutputStream().write("PUT|a|1\n".getBytes(StandardCharsets.UTF_8));
                queued.getOutputStream().write("GET|a\n".getBytes(StandardCharsets.UTF_8));
                queued.setSoTimeout(3000);
                assertEquals("VALUE|1", new BufferedReader(new InputStreamReader(
                        queued.getInputStream(), StandardCharsets.UTF_8)).readLine());
            }
        }
    }
}
