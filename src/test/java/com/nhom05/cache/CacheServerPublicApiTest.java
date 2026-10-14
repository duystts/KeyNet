package com.nhom05.cache;

import com.nhom05.cache.server.CacheServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test o NGOAI package server: dam bao module khac (client, replication...) bat duoc
 * CacheServer that bang API public, dung mau trong docs/DAC_TA_TRIEN_KHAI.md.
 */
class CacheServerPublicApiTest {

    @Test
    void otherPackagesCanStartRealServerOnRandomPort() throws Exception {
        CacheServer server = new CacheServer(0, 60, 1000);
        Thread t = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            assertTrue(server.awaitListening(3000));
            int port = server.localPort();
            assertTrue(port > 0);
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(3000);
                s.getOutputStream().write("PUT|k|v\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("OK", new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine());
            }
        } finally {
            server.shutdown();
        }
    }
}
