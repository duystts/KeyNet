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
}
