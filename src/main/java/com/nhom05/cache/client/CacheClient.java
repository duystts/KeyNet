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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Module 2 - Client ket noi cluster Cache Server qua TCP (Issue #6, #7).
 *
 * Gui toi server chinh cua key; server chinh loi (E004/E006, mat ket noi) thi thu server
 * du phong 1 lan. Mac dinh moi request mo socket moi (connection-per-request).
 * Ho tro che do Connection Pool (Keep-Alive) cho benchmark va do tai thong luong cao.
 */
public class CacheClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CacheClient.class.getName());

    /** Thoi gian cho ket noi va doc response (muc 8 dac ta). */
    public static final int TIMEOUT_MS = 2000;

    private final HashRouter router;
    private final int timeoutMs;
    private volatile boolean pooled;
    private volatile int maxPoolSize;
    private final Map<ServerAddress, BlockingQueue<PooledConnection>> connectionPools = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private static class PooledConnection {
        final Socket socket;
        final OutputStream out;
        final BufferedReader in;

        PooledConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        boolean isAlive() {
            return !socket.isClosed() && socket.isConnected();
        }
    }

    /**
     * Tao client voi danh sach server va timeout mac dinh 2000ms.
     *
     * @param servers danh sach dia chi Cache Server tu ServerConfig.servers()
     */
    public CacheClient(List<ServerAddress> servers) {
        this(servers, TIMEOUT_MS, false, 20);
    }

    /**
     * Tao client voi danh sach server va timeout tuy chinh (cho test).
     *
     * @param servers   danh sach dia chi Cache Server
     * @param timeoutMs timeout connect va read tinh bang ms
     */
    public CacheClient(List<ServerAddress> servers, int timeoutMs) {
        this(servers, timeoutMs, false, 20);
    }

    /**
     * Tao client voi tuy chon Connection Pool.
     *
     * @param servers     danh sach dia chi Cache Server
     * @param timeoutMs   timeout connect va read tinh bang ms
     * @param pooled      bat che do connection pool neu true
     * @param maxPoolSize so ket noi toi da giu cho moi server
     */
    public CacheClient(List<ServerAddress> servers, int timeoutMs, boolean pooled, int maxPoolSize) {
        this.router = new HashRouter(servers);
        this.timeoutMs = timeoutMs;
        this.pooled = pooled;
        this.maxPoolSize = Math.max(1, maxPoolSize);
    }

    /** Tao client bat san che do Connection Pool (Keep-Alive). */
    public static CacheClient pooled(List<ServerAddress> servers, int timeoutMs, int poolSize) {
        return new CacheClient(servers, timeoutMs, true, poolSize);
    }

    public boolean isPooled() {
        return pooled;
    }

    public synchronized void enableConnectionPool(int maxPoolSize) {
        this.pooled = true;
        this.maxPoolSize = Math.max(1, maxPoolSize);
    }

    public synchronized void disableConnectionPool() {
        this.pooled = false;
        close();
        this.closed = false;
    }

    /**
     * Tao client tu file cau hinh config.properties.
     *
     * @param path duong dan file cau hinh
     */
    public static CacheClient fromConfig(String path) throws IOException {
        ServerConfig config = ServerConfig.load(path);
        return new CacheClient(config.servers());
    }

    /** HashRouter dung de chon server chinh/du phong. */
    public HashRouter router() {
        return router;
    }

    /**
     * Ket qua 1 lan gui request.
     *
     * @param line       dong response nguyen ban tu server
     * @param servedBy   chi so server da tra loi
     * @param failedOver true neu da chuyen sang server du phong (#7)
     */
    public record Response(String line, int servedBy, boolean failedOver) {
    }

    /**
     * Gui 1 dong request toi server chinh cua key, tra ve Response.
     * Issue #7: neu server chinh loi co the failover (E004/E006, IOException, null response),
     * thi thu server du phong 1 lan. Ca 2 deu loi -> tra ERROR|E004.
     * Loi du lieu (E001, E002, E003, E005) tra luon, khong failover.
     *
     * @param key         key de chon server chinh (khong kiem tra do dai o day)
     * @param requestLine dong request da duoc build san (dung ProtocolParser.put/get/delete)
     * @return Response chua dong response tu server
     */
    public Response send(String key, String requestLine) {
        int primary = router.primaryIndex(key);
        ServerAddress primaryAddr = router.server(primary);
        String response = sendTo(primaryAddr, requestLine);

        // Kiem tra co nen failover khong
        ErrorCode code = ProtocolParser.errorCodeOf(response);
        boolean shouldFailover = (code != null && code.shouldFailover());

        if (!shouldFailover) {
            // Tra luon: OK, VALUE, NOTFOUND, hoac loi du lieu (E001/E002/E003/E005)
            return new Response(response, primary, false);
        }

        // Failover: thu server du phong 1 lan duy nhat
        int replica = router.replicaIndex(primary);
        if (replica == primary) {
            // Cum 1 server: khong co server du phong, gui lai cung server chi ton them 1 lan timeout
            return new Response(response, primary, false);
        }
        ServerAddress replicaAddr = router.server(replica);
        LOGGER.warning("Failover: server chinh " + primaryAddr.host() + ":" + primaryAddr.port()
                + " tra \"" + response + "\", thu server du phong "
                + replicaAddr.host() + ":" + replicaAddr.port());

        String replicaResponse = sendTo(replicaAddr, requestLine);
        ErrorCode replicaCode = ProtocolParser.errorCodeOf(replicaResponse);

        if (replicaCode != null && replicaCode.shouldFailover()) {
            // Ca 2 server deu loi -> tra ERROR|E004
            String finalError = ProtocolParser.error(ErrorCode.E004,
                    "Ca server chinh va du phong deu khong kha dung");
            return new Response(finalError, replica, true);
        }

        return new Response(replicaResponse, replica, true);
    }

    /**
     * Ghi key-value vao server chinh.
     *
     * Kiem tra key/value phia client truoc: key sai -> ERROR|E002, value sai -> ERROR|E003.
     * Khong gui request sai len mang.
     *
     * @return dong response tu server hoac ERROR|Exxx neu validate that bai
     */
    public String put(String key, String value) {
        String error = ProtocolParser.keyError(key);
        if (error == null) {
            error = ProtocolParser.valueError(value);
        }
        return error != null ? error : send(key, ProtocolParser.put(key, value)).line();
    }

    /**
     * Doc gia tri theo key tu server chinh.
     *
     * @return VALUE|... hoac NOTFOUND hoac ERROR|Exxx
     */
    public String get(String key) {
        String error = ProtocolParser.keyError(key);
        return error != null ? error : send(key, ProtocolParser.get(key)).line();
    }

    /**
     * Xoa key khoi server chinh.
     *
     * @return OK, NOTFOUND hoac ERROR|Exxx
     */
    public String delete(String key) {
        String error = ProtocolParser.keyError(key);
        return error != null ? error : send(key, ProtocolParser.delete(key)).line();
    }

    /**
     * Gui request toi 1 server cu the.
     * Neu bat connection pool thi muon ket noi tu pool, nguoc lai tao socket moi cho moi request.
     */
    private String sendTo(ServerAddress addr, String requestLine) {
        if (!pooled || closed) {
            return sendToSingleConnection(addr, requestLine);
        }
        return sendToPooledConnection(addr, requestLine);
    }

    private String sendToSingleConnection(ServerAddress addr, String requestLine) {
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(addr.host(), addr.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            // Ghi request
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Doc response (1 dong)
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String response = in.readLine();

            if (response == null) {
                // Server dong ket noi ma khong tra gi
                LOGGER.warning("Server " + addr.host() + ":" + addr.port()
                        + " dong ket noi ma khong tra response");
                return ProtocolParser.error(ErrorCode.E004,
                        "Khong ket noi duoc server " + addr.host() + ":" + addr.port());
            }
            return response;
        } catch (IOException e) {
            LOGGER.warning("Loi ket noi toi " + addr.host() + ":" + addr.port()
                    + ": " + e.getMessage());
            return ProtocolParser.error(ErrorCode.E004,
                    "Khong ket noi duoc server " + addr.host() + ":" + addr.port());
        }
    }

    private String sendToPooledConnection(ServerAddress addr, String requestLine) {
        BlockingQueue<PooledConnection> pool = connectionPools.computeIfAbsent(addr, k -> new ArrayBlockingQueue<>(maxPoolSize));

        // 1. Thu ket noi cu trong pool. Server dong ket noi keep-alive khi im lang qua lau hoac
        //    khi dang ban (#59) ma client khong biet -> ket noi cu hong thi bo, sang buoc 2.
        //    Timeout thi khong thu lai: server con song nhung cham, de send() failover.
        PooledConnection conn;
        while ((conn = pool.poll()) != null) {
            if (!conn.isAlive()) {
                conn.close();
                continue;
            }
            try {
                String response = exchange(conn, requestLine);
                if (response != null) {
                    release(pool, conn);
                    return response;
                }
                conn.close();
            } catch (SocketTimeoutException e) {
                conn.close();
                LOGGER.warning("Het thoi gian cho response pooled tu " + addr.host() + ":" + addr.port());
                return ProtocolParser.error(ErrorCode.E004,
                        "Khong ket noi duoc server " + addr.host() + ":" + addr.port());
            } catch (IOException e) {
                conn.close();
            }
        }

        // 2. Mo ket noi moi: loi o day moi that su la server khong kha dung
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(addr.host(), addr.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            conn = new PooledConnection(socket);
            String response = exchange(conn, requestLine);
            if (response == null) {
                conn.close();
                LOGGER.warning("Server " + addr.host() + ":" + addr.port()
                        + " dong ket noi ma khong tra response (pooled)");
                return ProtocolParser.error(ErrorCode.E004,
                        "Khong ket noi duoc server " + addr.host() + ":" + addr.port());
            }
            release(pool, conn);
            return response;
        } catch (IOException e) {
            if (conn != null) {
                conn.close();
            }
            LOGGER.warning("Loi ket noi pooled toi " + addr.host() + ":" + addr.port() + ": " + e.getMessage());
            return ProtocolParser.error(ErrorCode.E004,
                    "Khong ket noi duoc server " + addr.host() + ":" + addr.port());
        }
    }

    /** Gui 1 dong request, doc 1 dong response; null neu server da dong ket noi. */
    private static String exchange(PooledConnection conn, String requestLine) throws IOException {
        conn.out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
        conn.out.flush();
        return conn.in.readLine();
    }

    /** Tra ket noi lai pool neu chua day va client chua bi dong. */
    private void release(BlockingQueue<PooledConnection> pool, PooledConnection conn) {
        if (closed || !pool.offer(conn)) {
            conn.close();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (BlockingQueue<PooledConnection> queue : connectionPools.values()) {
            PooledConnection conn;
            while ((conn = queue.poll()) != null) {
                conn.close();
            }
        }
        connectionPools.clear();
    }
}
