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
 * Module 2 - Client ket noi cluster Cache Server qua TCP (Issue #6).
 *
 * Chi gửi toi server chinh; failover bo sung o Issue #7.
 * Moi request mo socket moi, ghi 1 dong UTF-8, doc 1 dong response, dong socket.
 */
public class CacheClient {

    private static final Logger LOGGER = Logger.getLogger(CacheClient.class.getName());

    /** Thoi gian cho ket noi va doc response (muc 8 dac ta). */
    public static final int TIMEOUT_MS = 2000;

    private final HashRouter router;
    private final int timeoutMs;

    /**
     * Tao client voi danh sach server va timeout mac dinh 2000ms.
     *
     * @param servers danh sach dia chi Cache Server tu ServerConfig.servers()
     */
    public CacheClient(List<ServerAddress> servers) {
        this(servers, TIMEOUT_MS);
    }

    /**
     * Tao client voi danh sach server va timeout tuy chinh (cho test).
     *
     * @param servers   danh sach dia chi Cache Server
     * @param timeoutMs timeout connect va read tinh bang ms
     */
    public CacheClient(List<ServerAddress> servers, int timeoutMs) {
        this.router = new HashRouter(servers);
        this.timeoutMs = timeoutMs;
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
     * Issue #6: chi gưi server chinh (failover bo sung o #7).
     *
     * @param key         key de chon server chinh (khong kiem tra do dai o day)
     * @param requestLine dong request da duoc build san (dung ProtocolParser.put/get/delete)
     * @return Response chua dong response tu server
     */
    public Response send(String key, String requestLine) {
        int primary = router.primaryIndex(key);
        ServerAddress addr = router.server(primary);
        String response = sendTo(addr, requestLine);
        return new Response(response, primary, false);
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
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        if (!ProtocolParser.isValueValid(value)) {
            return ProtocolParser.error(ErrorCode.E003);
        }
        return send(key, ProtocolParser.put(key, value)).line();
    }

    /**
     * Doc gia tri theo key tu server chinh.
     *
     * @return VALUE|... hoac NOTFOUND hoac ERROR|Exxx
     */
    public String get(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        return send(key, ProtocolParser.get(key)).line();
    }

    /**
     * Xoa key khoi server chinh.
     *
     * @return OK, NOTFOUND hoac ERROR|Exxx
     */
    public String delete(String key) {
        if (!ProtocolParser.isKeyValid(key)) {
            return ProtocolParser.error(ErrorCode.E002);
        }
        return send(key, ProtocolParser.delete(key)).line();
    }

    /**
     * Mo socket moi, gui 1 dong request, doc 1 dong response, dong socket.
     * Dung cau truc: new Socket() -> connect(addr, timeout) -> setSoTimeout(timeout)
     *               -> ghi line + "\n" UTF-8 -> doc 1 dong -> dong.
     */
    private String sendTo(ServerAddress addr, String requestLine) {
        try (Socket socket = new Socket()) {
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
                return ProtocolParser.error(ErrorCode.E004);
            }
            return response;
        } catch (IOException e) {
            LOGGER.warning("Loi ket noi toi " + addr.host() + ":" + addr.port()
                    + ": " + e.getMessage());
            return ProtocolParser.error(ErrorCode.E004);
        }
    }
}
