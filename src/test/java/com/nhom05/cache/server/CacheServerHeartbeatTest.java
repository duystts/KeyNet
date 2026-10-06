package com.nhom05.cache.server;

import com.nhom05.cache.registry.HeartbeatSender;
import com.nhom05.cache.registry.RegistryClient;
import com.nhom05.cache.registry.RegistryServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Cache Server gui heartbeat kem so key / so request toi Registry (Module 1 + 4, cho dashboard Module 5). */
class CacheServerHeartbeatTest {

    private static final int CACHE_PORT = 6201;
    private static final int REGISTRY_PORT = 6290;

    private CacheServer server;
    private RegistryServer registry;
    private HeartbeatSender heartbeat;

    @AfterEach
    void tearDown() {
        if (heartbeat != null) heartbeat.stop();
        if (server != null) server.shutdown();
        if (registry != null) registry.shutdown();
    }

    @Test
    void requestCountCountsEveryClientRequest() throws Exception {
        startServer();
        assertEquals(0, server.requestCount());
        send("PUT|a|1");
        send("GET|a");
        send("FOO");
        assertEquals(3, server.requestCount());
    }

    @Test
    void statsReflectServerKeyAndRequestCounts() throws Exception {
        registry = new RegistryServer(REGISTRY_PORT, 2000);
        registry.start();
        startServer();
        heartbeat = new HeartbeatSender("127.0.0.1", REGISTRY_PORT, 0, "127.0.0.1", CACHE_PORT, 1);
        heartbeat.setMetricsSuppliers(server.store()::size, server::requestCount);
        heartbeat.start();

        RegistryClient reg = new RegistryClient("127.0.0.1", REGISTRY_PORT);
        awaitTrue(() -> "UP".equals(reg.checkStatus(0)), "server chua dang ky voi Registry");

        send("PUT|a|1");
        send("PUT|b|2");
        send("GET|a");
        awaitTrue(() -> "STATS|0:UP:2:3".equals(reg.getRawStats()), "STATS chua cap nhat: " + reg.getRawStats());

        send("DELETE|a");
        send("DELETE|b");
        awaitTrue(() -> "STATS|0:UP:0:5".equals(reg.getRawStats()),
                "so key phai ve 0 sau khi xoa het: " + reg.getRawStats());

        heartbeat.stop();
        awaitTrue(() -> "DOWN".equals(reg.checkStatus(0)), "Registry khong phat hien server ngung heartbeat");
    }

    private void startServer() throws InterruptedException {
        server = new CacheServer(CACHE_PORT, 60, 100);
        new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        }).start();
        Thread.sleep(200);
    }

    private static String send(String line) throws IOException {
        try (Socket s = new Socket("127.0.0.1", CACHE_PORT)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static void awaitTrue(Supplier<Boolean> cond, String msg) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) return;
            Thread.sleep(50);
        }
        fail(msg);
    }
}
