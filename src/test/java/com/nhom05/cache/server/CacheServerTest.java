package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Chay CacheServer that tren port ngau nhien, gui request qua socket nhu client that. */
class CacheServerTest {

    private CacheServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    private int start(CacheServer s) throws InterruptedException {
        server = s;
        Thread t = new Thread(() -> {
            try {
                s.start();
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        assertTrue(s.awaitListening(3000), "server khong mo duoc port");
        return s.localPort();
    }

    private int startDefault() throws InterruptedException {
        return start(new CacheServer(0, 60, 1000));
    }

    private static String send(int port, byte[] data, boolean closeOutput) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write(data);
            out.flush();
            if (closeOutput) {
                s.shutdownOutput();
            }
            return reader.readLine();
        }
    }

    private static String send(int port, String line) throws IOException {
        return send(port, (line + "\n").getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    void putGetDeleteOverTcp() throws Exception {
        int port = startDefault();
        assertEquals("OK", send(port, "PUT|username|Duy"));
        assertEquals("VALUE|Duy", send(port, "GET|username"));
        assertEquals("OK", send(port, "DELETE|username"));
        assertEquals("NOTFOUND", send(port, "GET|username"));
        assertEquals("ERROR|E001|Invalid command syntax", send(port, "HELLO"));
    }

    @Test
    void utf8AndTelnetLineEndings() throws Exception {
        int port = startDefault();
        assertEquals("OK", send(port, "PUT|ten|Nguyễn Tiến Đức\r"));
        assertEquals("VALUE|Nguyễn Tiến Đức", send(port, "GET|ten\r"));
    }

    @Test
    void requestWithoutTrailingNewlineStillHandled() throws Exception {
        int port = startDefault();
        assertEquals("OK", send(port, "PUT|a|1".getBytes(StandardCharsets.UTF_8), true));
    }

    @Test
    void hugeLineIsRejectedWithE003AndServerStaysUp() throws Exception {
        int port = startDefault();
        byte[] huge = ("PUT|k|" + "x".repeat(100_000)).getBytes(StandardCharsets.UTF_8);
        assertEquals("ERROR|E003|Value exceeds max length", send(port, huge, true));
        assertEquals("OK", send(port, "PUT|k|v"));
    }

    @Test
    void manyConcurrentClients() throws Exception {
        int port = startDefault();
        AtomicInteger wrong = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        String k = "t" + id + "_" + i;
                        if (!"OK".equals(send(port, "PUT|" + k + "|v" + i))
                                || !("VALUE|v" + i).equals(send(port, "GET|" + k))) {
                            wrong.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    wrong.incrementAndGet();
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(0, wrong.get());
        assertEquals(500, server.store().size());
    }

    @Test
    void overloadedServerAnswersE004() throws Exception {
        // 1 thread + hang doi 1: ket noi thu 3 phai bi tu choi bang E004
        int port = start(new CacheServer(0, new KeyValueStore(60, 100), 1, 1));
        try (Socket busy = new Socket("127.0.0.1", port)) {        // chiem thread duy nhat (chua gui gi)
            Thread.sleep(150);
            try (Socket queued = new Socket("127.0.0.1", port)) {   // nam trong hang doi
                Thread.sleep(150);
                assertEquals("ERROR|E004|Server overloaded", send(port, "GET|x"));
                // Khi thread ranh, ket noi dang cho van duoc phuc vu binh thuong
                busy.getOutputStream().write("PUT|a|1\n".getBytes(StandardCharsets.UTF_8));
                queued.getOutputStream().write("GET|a\n".getBytes(StandardCharsets.UTF_8));
                queued.setSoTimeout(3000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        queued.getInputStream(), StandardCharsets.UTF_8))) {
                    assertEquals("VALUE|1", reader.readLine());
                }
            }
        }
    }

    @Test
    void rejectedConnectionsDoNotBlockAcceptThread() throws Exception {
        // #59: tra E004 khong duoc doi client gui request (truoc day moi ket noi bi tu choi
        // chan thread accept 100ms -> 20 ket noi im lang lam ket noi sau phai cho ~2s)
        int port = start(new CacheServer(0, new KeyValueStore(60, 100), 1, 1));
        List<Socket> silent = new ArrayList<>();
        try {
            for (int i = 0; i < 22; i++) {          // 1 chiem thread, 1 xep hang, 20 bi tu choi
                silent.add(new Socket("127.0.0.1", port));
            }
            long start = System.nanoTime();
            assertEquals("ERROR|E004|Server overloaded", send(port, "GET|x"));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 500, "E004 phai tra ngay, mat " + ms + "ms");
        } finally {
            for (Socket s : silent) {
                s.close();
            }
        }
    }

    @Test
    void shutdownReleasesPort() throws Exception {
        int port = startDefault();
        server.shutdown();
        Thread.sleep(200);
        assertThrows(ConnectException.class, () -> send(port, "GET|k"));
    }

    @Test
    void testKeyAndRequestCount() throws Exception {
        int port = startDefault();
        assertEquals(0, server.keyCount());
        assertEquals(0, server.requestCount());

        send(port, "PUT|k1|v1");
        send(port, "GET|k1");

        assertEquals(1, server.keyCount());
        assertEquals(2, server.requestCount());
    }
}
