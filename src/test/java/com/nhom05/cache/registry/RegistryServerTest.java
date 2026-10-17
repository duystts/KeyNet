package com.nhom05.cache.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RegistryServerTest {

    private RegistryServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testHandleCommandHeartbeat4Parts() {
        server = new RegistryServer(0);
        assertEquals("ACK", server.handleCommand("HEARTBEAT|0|127.0.0.1|5001"));
        assertEquals("UP", server.handleCommand("STATUS|0"));
        assertEquals("STATS|0:UP:0:0", server.handleCommand("STATS"));
    }

    @Test
    void testHandleCommandHeartbeat6Parts() {
        server = new RegistryServer(0);
        assertEquals("ACK", server.handleCommand("HEARTBEAT|0|127.0.0.1|5001|12|340"));
        assertEquals("UP", server.handleCommand("STATUS|0"));
        assertEquals("STATS|0:UP:12:340", server.handleCommand("STATS"));
    }

    @Test
    void testHandleCommandStatusUnknownServer() {
        server = new RegistryServer(0);
        assertEquals("DOWN", server.handleCommand("STATUS|99"));
    }

    @Test
    void testHandleCommandStatsEmpty() {
        server = new RegistryServer(0);
        assertEquals("STATS|", server.handleCommand("STATS"));
    }

    @Test
    void testHandleCommandStatsSortedByIndex() {
        server = new RegistryServer(0);
        server.handleCommand("HEARTBEAT|2|127.0.0.1|5003|30|300");
        server.handleCommand("HEARTBEAT|0|127.0.0.1|5001|10|100");
        server.handleCommand("HEARTBEAT|1|127.0.0.1|5002|20|200");

        assertEquals("STATS|0:UP:10:100;1:UP:20:200;2:UP:30:300", server.handleCommand("STATS"));
    }

    @Test
    void testHandleCommandInvalidSyntax() {
        server = new RegistryServer(0);
        assertTrue(server.handleCommand(null).startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("   ").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("UNKNOWN|CMD").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("HEARTBEAT|not-a-number|127.0.0.1|5001").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("HEARTBEAT|0|127.0.0.1|invalid-port").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("HEARTBEAT|0|127.0.0.1|5001|invalid-key-count|100").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("STATUS|abc").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("STATUS").startsWith("ERROR|E001"));
        assertTrue(server.handleCommand("STATS|extra").startsWith("ERROR|E001"));
    }

    @Test
    void testTimeoutAndRecovery() throws InterruptedException {
        // Timeout ngan 250ms de test nhanh
        server = new RegistryServer(0, 250);
        assertEquals("ACK", server.handleCommand("HEARTBEAT|0|127.0.0.1|5001|15|250"));
        assertEquals("UP", server.handleCommand("STATUS|0"));

        // Doi vuot qua timeoutMs
        Thread.sleep(350);
        assertEquals("DOWN", server.handleCommand("STATUS|0"));
        assertEquals("STATS|0:DOWN:15:250", server.handleCommand("STATS"));

        // Gui lai heartbeat -> phuc hoi UP va reset so lieu ve 0
        assertEquals("ACK", server.handleCommand("HEARTBEAT|0|127.0.0.1|5001|0|0"));
        assertEquals("UP", server.handleCommand("STATUS|0"));
        assertEquals("STATS|0:UP:0:0", server.handleCommand("STATS"));
    }

    @Test
    void testRealSocketAndPersistentConnection() throws IOException {
        server = new RegistryServer(0);
        server.start();
        int port = server.localPort();
        assertTrue(port > 0);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Gui lenh 1 tren ket noi
            out.write("HEARTBEAT|0|127.0.0.1|5001|5|50\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("ACK", in.readLine());

            // Gui lenh 2 tren cung ket noi (persistent connection)
            out.write("STATUS|0\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("UP", in.readLine());

            // Gui lenh 3
            out.write("STATS\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("STATS|0:UP:5:50", in.readLine());
        }
    }

    @Test
    void testOverloadedServerAnswersE004AndKeepsAccepting() throws Exception {
        // 1 worker thread + 1 hang doi: ket noi thu 3 se bi tu choi bang ERROR|E004
        server = new RegistryServer(0, 9000, 1, 1);
        server.start();
        int port = server.localPort();
        assertTrue(port > 0);

        try (Socket busy = new Socket("127.0.0.1", port)) {
            Thread.sleep(100);
            try (Socket queued = new Socket("127.0.0.1", port)) {
                Thread.sleep(100);
                try (Socket rejected = new Socket("127.0.0.1", port)) {
                    rejected.setSoTimeout(3000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(rejected.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    assertEquals("ERROR|E004|Server overloaded", line);
                }

                // busy gui lenh roi dong ket noi, giai phong worker duy nhat cho ket noi trong hang doi
                busy.getOutputStream().write("HEARTBEAT|0|127.0.0.1|5001\n".getBytes(StandardCharsets.UTF_8));
                busy.getOutputStream().flush();
                BufferedReader busyIn = new BufferedReader(new InputStreamReader(busy.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("ACK", busyIn.readLine());
                busy.close();

                queued.getOutputStream().write("STATUS|0\n".getBytes(StandardCharsets.UTF_8));
                queued.getOutputStream().flush();
                queued.setSoTimeout(3000);
                BufferedReader queuedIn = new BufferedReader(new InputStreamReader(queued.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("UP", queuedIn.readLine());
            }
        }

        // Sau khi tat ca cac ket noi tren dong, accept thread van song va nhan ket noi moi
        try (Socket fresh = new Socket("127.0.0.1", port)) {
            fresh.setSoTimeout(3000);
            fresh.getOutputStream().write("STATUS|0\n".getBytes(StandardCharsets.UTF_8));
            fresh.getOutputStream().flush();
            BufferedReader freshIn = new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("UP", freshIn.readLine());
        }
    }

    @Test
    void test130ConnectionsDoNotKillAcceptThread() throws Exception {
        server = new RegistryServer(0);
        server.start();
        int port = server.localPort();
        assertTrue(port > 0);

        java.util.List<Socket> sockets = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 130; i++) {
                sockets.add(new Socket("127.0.0.1", port));
            }
            // Cho mot chut de cac ket noi duoc accept va rejected xu ly
            Thread.sleep(200);
        } finally {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }

        // Sau khi dong het ket noi, Registry van tiep tuc accept binh thuong
        try (Socket fresh = new Socket("127.0.0.1", port)) {
            fresh.setSoTimeout(3000);
            fresh.getOutputStream().write("HEARTBEAT|0|127.0.0.1|5001\n".getBytes(StandardCharsets.UTF_8));
            fresh.getOutputStream().flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("ACK", in.readLine());
        }
    }
}
