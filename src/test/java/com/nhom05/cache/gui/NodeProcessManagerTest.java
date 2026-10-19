package com.nhom05.cache.gui;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NodeProcessManagerTest {

    @Test
    void testShutdownNodeSuccessWithMockServer() throws Exception {
        CountDownLatch shutdownReceived = new CountDownLatch(1);
        AtomicBoolean receivedCorrectCommand = new AtomicBoolean(false);

        // Tao mock replication server tren port 0 (OS tu cap port)
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket client = serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    String line = in.readLine();
                    if ("SHUTDOWN".equalsIgnoreCase(line != null ? line.strip() : "")) {
                        receivedCorrectCommand.set(true);
                        out.print("OK\n");
                        out.flush();
                        shutdownReceived.countDown();
                    }
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Goi ham shutdownNode
            boolean result = NodeProcessManager.shutdownNode("127.0.0.1", port, 2000);
            assertTrue(result, "shutdownNode phai tra ve true khi nhan duoc OK tu server");
            assertTrue(shutdownReceived.await(2, TimeUnit.SECONDS), "Server phai nhan duoc lenh SHUTDOWN");
            assertTrue(receivedCorrectCommand.get(), "Lenh nhan duoc phai la SHUTDOWN");
        }
    }

    @Test
    void testShutdownNodeConnectionRefused() {
        // Port khong co ai lang nghe (vi du 59999) phai tra ve false ma khong nem Exception
        boolean result = NodeProcessManager.shutdownNode("127.0.0.1", 59999, 500);
        assertFalse(result, "shutdownNode phai tra ve false khi khong ket noi duoc");
    }

    @Test
    void testShutdownNodeInvalidArguments() {
        assertFalse(NodeProcessManager.shutdownNode(null, 6001));
        assertFalse(NodeProcessManager.shutdownNode("127.0.0.1", -1));
        assertFalse(NodeProcessManager.shutdownNode("127.0.0.1", 0));
    }

    @Test
    void testJavaResolution() {
        String javaBin = NodeProcessManager.resolveJavaExecutable();
        assertNotNull(javaBin);
        assertFalse(javaBin.isBlank());

        String classPath = NodeProcessManager.resolveClassPath();
        assertNotNull(classPath);
        assertFalse(classPath.isBlank());
    }

    @Test
    void testProcessLifecycleTracking() {
        assertFalse(NodeProcessManager.isProcessAlive(99));
        assertNull(NodeProcessManager.getManagedProcess(99));

        NodeProcessManager.stopAllManagedProcesses();
        assertFalse(NodeProcessManager.isProcessAlive(99));
    }
}
