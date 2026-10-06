package com.nhom05.cache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Doc request qua socket that: dong qua dai, CRLF (telnet), ket noi rong. */
@SuppressWarnings("resource")
class ClientHandlerSocketTest {

    private static final int PORT = 6203;
    private CacheServer server;

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new CacheServer(PORT, 60, 100);
        new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        }).start();
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() {
        server.shutdown();
    }

    private static String sendRaw(byte[] data) throws IOException {
        try (Socket s = new Socket("127.0.0.1", PORT)) {
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write(data);
            out.flush();
            s.shutdownOutput();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    @Test
    void hugeLineWithoutNewlineIsRejectedWithE003() throws IOException {
        byte[] huge = ("PUT|k|" + "x".repeat(100_000)).getBytes(StandardCharsets.UTF_8);
        assertEquals("ERROR|E003|Value exceeds max length", sendRaw(huge));
        // server van song binh thuong
        assertEquals("OK", sendRaw("PUT|k|v\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void maxSizeValueStillAccepted() throws IOException {
        String value = "x".repeat(4096);
        assertEquals("OK", sendRaw(("PUT|" + "k".repeat(256) + "|" + value + "\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals("VALUE|" + value, sendRaw(("GET|" + "k".repeat(256) + "\n").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void telnetCrlfLineEndingsWork() throws IOException {
        assertEquals("OK", sendRaw("PUT|ten|Duy\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("VALUE|Duy", sendRaw("GET|ten\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void requestWithoutTrailingNewlineIsStillHandled() throws IOException {
        try (Socket s = new Socket("127.0.0.1", PORT)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write("PUT|a|1".getBytes(StandardCharsets.UTF_8));
            s.shutdownOutput();
            assertEquals("OK", new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine());
        }
    }
}
