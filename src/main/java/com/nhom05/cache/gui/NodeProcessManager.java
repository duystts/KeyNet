package com.nhom05.cache.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Module 5 - Issue #16: Quan ly vong doi tien trinh Cache Server that (Sap node / Bat lai node).
 *
 * Dac ta ky thuat (#16):
 * - "Sap node": Gui lenh SHUTDOWN\n qua TCP toi port replication cua server do (cổng client + 1000).
 *   Server tra loi "OK" roi tu dong ket thuc bang Runtime.getRuntime().halt(0).
 * - "Bat lai node": Chay server o tien trinh rieng bang ProcessBuilder:
 *   java -cp <classpath> com.nhom05.cache.server.CacheServer config/config.properties <serverIndex>
 *   Ghi log tien trinh ra logs/server-<serverIndex>.log. Server moi tu dong SYNC lay lai du lieu (#10).
 */
public final class NodeProcessManager {

    private static final Logger LOGGER = Logger.getLogger(NodeProcessManager.class.getName());
    private static final int DEFAULT_TIMEOUT_MS = 2000;

    /** Luu tru cac tien trinh con duoc bat tu giao dien Dashboard. */
    private static final Map<Integer, Process> MANAGED_PROCESSES = new ConcurrentHashMap<>();

    private NodeProcessManager() {
    }

    /**
     * Gui lenh SHUTDOWN toi port replication cua mot Cache Server de tat tien trinh that.
     *
     * @param host            dia chi IP cua server can tat (thuong la 127.0.0.1 hoac localhost)
     * @param replicationPort cong replication (thuong la clientPort + 1000)
     * @return true neu server phan hoi OK va dong y shutdown, nguoc lai false
     */
    public static boolean shutdownNode(String host, int replicationPort) {
        return shutdownNode(host, replicationPort, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Gui lenh SHUTDOWN kem thoi gian cho timeout tuy chinh.
     */
    public static boolean shutdownNode(String host, int replicationPort, int timeoutMs) {
        if (host == null || replicationPort <= 0) {
            return false;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, replicationPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            out.print("SHUTDOWN\n");
            out.flush();

            String response = in.readLine();
            boolean ok = response != null && response.strip().equalsIgnoreCase("OK");
            if (ok) {
                LOGGER.info("Da gui SHUTDOWN thanh cong toi " + host + ":" + replicationPort);
            } else {
                LOGGER.warning("Server tai " + host + ":" + replicationPort + " tu choi SHUTDOWN: " + response);
            }
            return ok;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Khong the gui SHUTDOWN toi " + host + ":" + replicationPort + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Khoi dong mot instance Cache Server o tien trinh rieng bang ProcessBuilder.
     * Log duoc ghi vao logs/server-<index>.log.
     *
     * @param serverIndex chi so server (0, 1, 2...)
     * @param configPath  duong dan toi file cau hinh (mac dinh config/config.properties)
     * @return Process dai dien cho tien trinh server con vua duoc tao
     * @throws IOException neu khong the khoi dong tien trinh
     */
    public static Process startNode(int serverIndex, String configPath) throws IOException {
        return startNode(serverIndex, configPath, resolveJavaExecutable(), resolveClassPath());
    }

    /**
     * Khoi dong Cache Server voi cac tham so javaCommand va classPath tuy chinh.
     */
    public static Process startNode(int serverIndex, String configPath, String javaCmd, String classPath) throws IOException {
        File logDir = new File("logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        File logFile = new File(logDir, "server-" + serverIndex + ".log");
        String finalConfigPath = (configPath != null && !configPath.isBlank()) ? configPath : "config/config.properties";

        ProcessBuilder pb = new ProcessBuilder(
                javaCmd,
                "-cp", classPath,
                "com.nhom05.cache.server.CacheServer",
                finalConfigPath,
                String.valueOf(serverIndex)
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        Process process = pb.start();
        MANAGED_PROCESSES.put(serverIndex, process);
        LOGGER.info("Da bat Cache Server #" + serverIndex + " qua ProcessBuilder (PID: " + process.pid() + ", log: " + logFile.getPath() + ")");
        return process;
    }

    /**
     * Kiem tra mot server index co tien trinh dang duoc quan ly va con song hay khong.
     */
    public static boolean isProcessAlive(int serverIndex) {
        Process p = MANAGED_PROCESSES.get(serverIndex);
        return p != null && p.isAlive();
    }

    /**
     * Lay doi tuong Process cua serverIndex neu duoc bat tu Dashboard.
     */
    public static Process getManagedProcess(int serverIndex) {
        return MANAGED_PROCESSES.get(serverIndex);
    }

    /**
     * Dung toan bo cac tien trinh CacheServer da duoc bat tu Dashboard.
     */
    public static void stopAllManagedProcesses() {
        for (Map.Entry<Integer, Process> entry : MANAGED_PROCESSES.entrySet()) {
            Process p = entry.getValue();
            if (p != null && p.isAlive()) {
                p.destroy();
            }
        }
        MANAGED_PROCESSES.clear();
    }

    /**
     * Xac dinh duong dan file thuc thi java tren he thong.
     */
    public static String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            File javaBin = new File(javaHome, "bin/java.exe");
            if (javaBin.exists()) {
                return javaBin.getAbsolutePath();
            }
            javaBin = new File(javaHome, "bin/java");
            if (javaBin.exists()) {
                return javaBin.getAbsolutePath();
            }
        }
        return "java";
    }

    /**
     * Lay classpath hien tai cua ung dung.
     */
    public static String resolveClassPath() {
        String cp = System.getProperty("java.class.path");
        return (cp != null && !cp.isBlank()) ? cp : "target/classes";
    }
}
