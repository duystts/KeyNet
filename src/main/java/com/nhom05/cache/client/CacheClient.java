package com.nhom05.cache.client;

import com.nhom05.cache.common.ErrorCode;
import com.nhom05.cache.common.ProtocolParser;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.common.ServerConfig.ServerAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Module 2 - Cache Client + Failover.
 *
 * Client ket noi toi Cluster Cache Server voi cac tinh nang:
 * 1. Tinh dinh tuyen hashing (hash(key) mod N) qua HashRouter.
 * 2. Gui request va nhan response qua TCP Socket (connection-per-request).
 * 3. Socket timeout default 2000ms (2 giay) theo muc 8 dac ta.
 * 4. Automatic Failover: Chuyen sang server du phong (serverIndex + 1) mod N
 *    khi server chinh timeout, ngat ket noi, hoac tra ve loi E004 (overloaded) / E006 (syncing).
 */
public class CacheClient {

    private static final Logger LOGGER = Logger.getLogger(CacheClient.class.getName());
    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private final HashRouter router;
    private final int timeoutMs;
    private volatile java.util.Set<Integer> simulatedDeadNodes;

    public CacheClient(ServerConfig config) {
        this(config.servers(), DEFAULT_TIMEOUT_MS);
    }

    public CacheClient(List<ServerAddress> servers) {
        this(servers, DEFAULT_TIMEOUT_MS);
    }

    public CacheClient(List<ServerAddress> servers, int timeoutMs) {
        this.router = new HashRouter(servers);
        this.timeoutMs = timeoutMs;
    }

    public void setSimulatedDeadNodes(java.util.Set<Integer> simulatedDeadNodes) {
        this.simulatedDeadNodes = simulatedDeadNodes;
    }

    public static CacheClient loadFromConfig(String configPath) throws IOException {
        ServerConfig config = ServerConfig.load(configPath);
        return new CacheClient(config);
    }

    public HashRouter getRouter() {
        return router;
    }

    /**
     * Ghi key-value vao cache cluster (PUT).
     */
    public String put(String key, String value) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        if (!ProtocolParser.isValueValid(value)) {
            return ProtocolParser.error(ErrorCode.E003);
        }
        String requestLine = "PUT|" + key + "|" + value;
        return executeWithFailover(key, requestLine);
    }

    /**
     * Doc gia tri cua key tu cache cluster (GET).
     */
    public String get(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        String requestLine = "GET|" + key;
        return executeWithFailover(key, requestLine);
    }

    /**
     * Xoa key khoi cache cluster (DELETE).
     */
    public String delete(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        String requestLine = "DELETE|" + key;
        return executeWithFailover(key, requestLine);
    }

    /**
     * Thuc hien chuoi lenh raw tu dung dinh dang (vd: PUT|key|val, GET|key).
     */
    public String sendRaw(String key, String rawCommand) {
        return executeWithFailover(key, rawCommand);
    }

    /**
     * Xu ly gui command den Server chinh, neu loi retry sang Server du phong.
     */
    private String executeWithFailover(String key, String requestLine) {
        int primaryIndex = router.getPrimaryServerIndex(key);
        ServerAddress primaryServer = router.getPrimaryServer(key);

        LOGGER.fine(() -> "Gui request den Server chinh #" + primaryIndex + " (" + primaryServer.host() + ":" + primaryServer.port() + ")");

        // Dem 1: Thu server chinh
        try {
            String response = sendToSocket(primaryServer, requestLine);
            if (response != null && !shouldFailover(response)) {
                return response;
            }
            if (response != null && shouldFailover(response)) {
                LOGGER.warning("Server chinh #" + primaryIndex + " tra ve loi failover: " + response + ". Dang kich hoat Failover...");
            }
        } catch (IOException e) {
            LOGGER.warning("Khong the ket noi/timeout toi Server chinh #" + primaryIndex + " (" + e.getMessage() + "). Dang kich hoat Failover...");
        }

        // Dem 2: Retry sang Server du phong (replica)
        int replicaIndex = router.getReplicaServerIndex(primaryIndex);
        ServerAddress replicaServer = router.getReplicaServer(primaryIndex);

        LOGGER.info(() -> "Failover: Gui request sang Server du phong #" + replicaIndex + " (" + replicaServer.host() + ":" + replicaServer.port() + ")");

        try {
            String replicaResponse = sendToSocket(replicaServer, requestLine);
            if (replicaResponse != null) {
                return replicaResponse;
            }
        } catch (IOException e) {
            LOGGER.severe("Loi ket noi toi Server du phong #" + replicaIndex + ": " + e.getMessage());
        }

        // Ca 2 server deu that bai
        return ProtocolParser.error(ErrorCode.E004, "Ca server chinh (#" + primaryIndex + ") va server du phong (#" + replicaIndex + ") deu khong the ket noi");
    }

    /**
     * Mo socket TCP, gui 1 dong request va doc 1 dong response.
     */
    private String sendToSocket(ServerAddress server, String requestLine) throws IOException {
        if (simulatedDeadNodes != null) {
            int index = router.getServers().indexOf(server);
            if (index >= 0 && simulatedDeadNodes.contains(index)) {
                throw new IOException("Connection refused: Simulated Dead Node #" + index);
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(server.host(), server.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            return reader.readLine();
        }
    }

    /**
     * Kiem tra response xem co phai cac ma loi E004 (overloaded) hoac E006 (syncing) can failover hay khong.
     * Theo muc 3 cua dac ta: E004 va E006 nen kich hoat failover/retry.
     */
    private boolean shouldFailover(String response) {
        if (response == null) {
            return true;
        }
        return response.startsWith("ERROR|" + ErrorCode.E004.code())
                || response.startsWith("ERROR|" + ErrorCode.E006.code());
    }
}
