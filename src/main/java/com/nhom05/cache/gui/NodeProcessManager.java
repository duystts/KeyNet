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
    /** Luu tru tien trinh Registry Server neu duoc bat tu Dashboard. */
    private static volatile Process managedRegistryProcess;

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
     * Kiem tra nhanh xem mot cong TCP co dang duoc lang nghe hay khong.
     *
     * @param host      dia chi host
     * @param port      cong can kiem tra
     * @param timeoutMs thoi gian cho ket noi
     * @return true neu ket noi thanh cong (dang lang nghe), false neu bi tu choi hoac timeout
     */
    public static boolean isPortListening(String host, int port, int timeoutMs) {
        if (host == null || port <= 0) {
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), Math.max(50, timeoutMs));
            return true;
        } catch (IOException e) {
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
     * Khoi dong Registry Server o tien trinh rieng bang ProcessBuilder.
     * Log duoc ghi vao logs/registry.log.
     *
     * @param port cong Registry (mac dinh 6000)
     * @return Process dai dien cho tien trinh Registry Server vua duoc tao
     * @throws IOException neu khong the khoi dong tien trinh
     */
    public static Process startRegistry(int port) throws IOException {
        return startRegistry(port, resolveJavaExecutable(), resolveClassPath());
    }

    /**
     * Khoi dong Registry Server voi cac tham so javaCommand va classPath tuy chinh.
     */
    public static Process startRegistry(int port, String javaCmd, String classPath) throws IOException {
        File logDir = new File("logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        File logFile = new File(logDir, "registry.log");
        ProcessBuilder pb = new ProcessBuilder(
                javaCmd,
                "-cp", classPath,
                "com.nhom05.cache.registry.RegistryServer",
                String.valueOf(port)
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        Process process = pb.start();
        managedRegistryProcess = process;
        LOGGER.info("Da bat Registry Server (port " + port + ") qua ProcessBuilder (PID: " + process.pid() + ", log: " + logFile.getPath() + ")");
        return process;
    }

    /**
     * Kiem tra tien trinh Registry co dang duoc quan ly va con song hay khong.
     */
    public static boolean isRegistryProcessAlive() {
        Process p = managedRegistryProcess;
        return p != null && p.isAlive();
    }

    /**
     * Lay doi tuong Process cua Registry neu duoc bat tu Dashboard.
     */
    public static Process getManagedRegistryProcess() {
        return managedRegistryProcess;
    }

    /**
     * Dung toan bo cac tien trinh CacheServer va Registry da duoc bat tu Dashboard.
     */
    public static void stopAllManagedProcesses() {
        for (Map.Entry<Integer, Process> entry : MANAGED_PROCESSES.entrySet()) {
            Process p = entry.getValue();
            if (p != null && p.isAlive()) {
                p.destroy();
            }
        }
        MANAGED_PROCESSES.clear();

        Process reg = managedRegistryProcess;
        if (reg != null && reg.isAlive()) {
            reg.destroy();
            managedRegistryProcess = null;
        }
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
     * Doc dong cuoi cung khac rong tu file log cua server tuong ung (logs/server-<serverIndex>.log).
     *
     * @param serverIndex chi so server
     * @return dong log cuoi cung hoac null neu file khong ton tai hoac rong
     */
    public static String readLastLogLine(int serverIndex) {
        File logFile = new File("logs", "server-" + serverIndex + ".log");
        if (!logFile.exists() || !logFile.isFile()) {
            return null;
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Doc dong cuoi cung khac rong tu file log cua Registry Server (logs/registry.log).
     */
    public static String readLastRegistryLogLine() {
        File logFile = new File("logs", "registry.log");
        if (!logFile.exists() || !logFile.isFile()) {
            return null;
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Lay classpath hien tai cua ung dung.
     */
    public static String resolveClassPath() {
        String cp = System.getProperty("java.class.path");
        return (cp != null && !cp.isBlank()) ? cp : "target/classes";
    }
}
