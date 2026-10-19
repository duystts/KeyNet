package com.nhom05.cache.server;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig;
import com.nhom05.cache.registry.RegistryClient;
import com.nhom05.cache.registry.RegistryServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tich hop #13: Registry + 3 CacheNode that (Module 1 + 3 + 4) doc tu 1 file config,
 * client that (Module 2) gui PUT/GET qua mang.
 */
class ClusterIntegrationTest {

    private static final int N = 3;
    /** Registry timeout ngan + heartbeat 1 giay de test khong phai cho 9 giay. */
    private static final long REGISTRY_TIMEOUT_MS = 2500;

    @TempDir
    Path tmp;

    private RegistryServer registry;
    private RegistryClient registryClient;
    private final CacheNode[] nodes = new CacheNode[N];
    private ServerConfig config;
    private Path configFile;
    private CacheClient client;

    @BeforeEach
    void startCluster() throws Exception {
        registry = new RegistryServer(0, REGISTRY_TIMEOUT_MS);
        registry.start();
        registryClient = new RegistryClient("127.0.0.1", registry.localPort());

        int[] ports = freeClientPorts(N);
        StringBuilder props = new StringBuilder();
        for (int i = 0; i < N; i++) {
            props.append("server.").append(i).append(".host=127.0.0.1\n");
            props.append("server.").append(i).append(".port=").append(ports[i]).append('\n');
        }
        props.append("registry.host=127.0.0.1\nregistry.port=").append(registry.localPort()).append('\n');
        configFile = tmp.resolve("config.properties");
        Files.writeString(configFile, props.toString());
        config = ServerConfig.load(configFile.toString());

        for (int i = 0; i < N; i++) {
            nodes[i] = startNode(i);
        }
        client = CacheClient.fromConfig(configFile.toString());
    }

    @AfterEach
    void stopCluster() {
        for (CacheNode node : nodes) {
            if (node != null) {
                node.close();
            }
        }
        registry.shutdown();
    }

    private CacheNode startNode(int index) throws IOException {
        CacheNode node = new CacheNode(config.withServerIndex(index), 1);
        node.start();
        return node;
    }

    @Test
    void putGetQuaClientVaDuocNhanBanSangServerDuPhong() throws Exception {
        Map<String, String> expected = writeKeys("k", 60);
        assertAllReadable(expected);

        // Moi key nam o server chinh va duoc replicate sang server (i+1) mod N
        for (Map.Entry<String, String> e : expected.entrySet()) {
            int primary = client.router().primaryIndex(e.getKey());
            int replica = client.router().replicaIndex(primary);
            assertEquals(e.getValue(), nodes[primary].server().store().get(e.getKey()));
            assertTrue(waitFor(() -> e.getValue().equals(nodes[replica].server().store().get(e.getKey()))),
                    "key " + e.getKey() + " chua duoc nhan ban sang server #" + replica);
        }

        // Registry thay ca 3 server UP kem so lieu that
        assertTrue(waitFor(() -> registryClient.stats().size() == N
                && registryClient.stats().stream().allMatch(s -> s.up() && s.requestCount() > 0)));
    }

    @Test
    void serverChetThiFailoverRegistryBaoDownBatLaiDocDungDuLieuMoi() throws Exception {
        Map<String, String> expected = writeKeys("user", 60);
        waitReplicated(expected);

        // Server #0 chet
        nodes[0].close();
        nodes[0] = null;
        assertTrue(waitFor(() -> "DOWN".equals(registryClient.status(0))), "Registry phai phat hien #0 chet");

        // Trong luc #0 chet: sua, xoa, them key cua #0 -> client tu failover sang #1
        int changed = 0;
        for (String key : expected.keySet().toArray(new String[0])) {
            if (client.router().primaryIndex(key) != 0) {
                continue;
            }
            CacheClient.Response r;
            if (changed % 2 == 0) {
                r = client.send(key, "PUT|" + key + "|sửa lúc #0 chết");
                expected.put(key, "sửa lúc #0 chết");
            } else {
                r = client.send(key, "DELETE|" + key);
                expected.put(key, null);
            }
            assertTrue(r.failedOver(), "phai failover khi #0 chet");
            assertEquals(1, r.servedBy());
            changed++;
        }
        assertTrue(changed >= 4, "can it nhat vai key thuoc #0 de kiem tra");
        for (int i = 0, added = 0; added < 5; i++) {
            String key = "moi" + i;
            if (client.router().primaryIndex(key) == 0) {
                assertEquals("OK", client.put(key, "thêm lúc #0 chết"));
                expected.put(key, "thêm lúc #0 chết");
                added++;
            }
        }

        // Bat lai #0: tu recover truoc khi nhan client
        nodes[0] = startNode(0);
        assertTrue(waitFor(() -> "UP".equals(registryClient.status(0))), "Registry phai thay #0 song lai");

        // Doc lai toan bo: key cua #0 phai do chinh #0 tra (khong failover) va dung gia tri moi nhat
        for (Map.Entry<String, String> e : expected.entrySet()) {
            CacheClient.Response r = client.send(e.getKey(), "GET|" + e.getKey());
            String want = e.getValue() == null ? "NOTFOUND" : "VALUE|" + e.getValue();
            assertEquals(want, r.line(), "key " + e.getKey());
            assertFalse(r.failedOver(), "khong duoc failover khi ca cum dang song: " + e.getKey());
        }
    }

    @Test
    void lenhShutdownTrenPortReplicationTatServerThat() throws Exception {
        nodes[2].setShutdownAction(nodes[2]::close);
        String key = keyOwnedBy(2);
        assertEquals("OK", client.put(key, "v"));

        assertEquals("OK", sendLine(config.replicationPort(2), "SHUTDOWN"));
        assertTrue(waitFor(() -> client.send(key, "GET|" + key).failedOver()),
                "sau SHUTDOWN, key cua #2 phai duoc tra boi server du phong");
        assertEquals("VALUE|v", client.get(key));
        assertTrue(waitFor(() -> "DOWN".equals(registryClient.status(2))));
    }

    @Test
    void testFailoverWriteReplicatesBackToPrimaryNcScenario() throws Exception {
        // Tái hiện kịch bản shell commands (nc) trong yêu cầu:
        // printf 'PUT|username|ban-dau\n' | nc 127.0.0.1 5001 # OK (#0 là primary)
        // printf 'PUT|username|ban-moi\n' | nc 127.0.0.1 5002 # OK (client failover sang #1)
        // printf 'GET|username\n' | nc 127.0.0.1 5001 # EXPECT: VALUE|ban-moi <- #0 phải trả giá trị mới
        String key = "username";
        assertEquals(0, client.router().primaryIndex(key), "username phai thuoc phan vung primary cua server #0");

        int port0 = config.servers().get(0).port();
        int port1 = config.servers().get(1).port();

        // 1. Ghi ban-dau toi server #0 (primary)
        assertEquals("OK", sendLine(port0, "PUT|" + key + "|ban-dau"));

        // Doi ban tin replicate sang #1
        assertTrue(waitFor(() -> "ban-dau".equals(nodes[1].server().store().get(key))));
        assertEquals("VALUE|ban-dau", sendLine(port0, "GET|" + key));

        // 2. Ghi ban-moi toi server #1 (gia lap client failover sang replica #1)
        assertEquals("OK", sendLine(port1, "PUT|" + key + "|ban-moi"));

        // Server #1 phai replicate ban-moi ve Primary #0 (thay vi ban tiep sang #2)
        assertTrue(waitFor(() -> "ban-moi".equals(nodes[0].server().store().get(key))),
                "Primary #0 phai nhan duoc ban ghi replicate tu replica #1");

        // 3. Doc tu server #0 (primary): phai tra ve ban-moi
        assertEquals("VALUE|ban-moi", sendLine(port0, "GET|" + key),
                "Doc truc tiep tu primary #0 phai tra ve VALUE|ban-moi sau failover write");

        // 4. CacheClient doc GET cung phai tra ve ban-moi tu server #0 (khong failover)
        CacheClient.Response r = client.send(key, "GET|" + key);
        assertEquals("VALUE|ban-moi", r.line());
        assertEquals(0, r.servedBy());
        assertFalse(r.failedOver());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> writeKeys(String prefix, int count) {
        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String key = prefix + i;
            String value = "giá trị " + i;
            assertEquals("OK", client.put(key, value));
            expected.put(key, value);
        }
        return expected;
    }

    private void assertAllReadable(Map<String, String> expected) {
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertEquals("VALUE|" + e.getValue(), client.get(e.getKey()));
        }
    }

    private void waitReplicated(Map<String, String> expected) throws InterruptedException {
        for (Map.Entry<String, String> e : expected.entrySet()) {
            int replica = client.router().replicaIndex(client.router().primaryIndex(e.getKey()));
            assertTrue(waitFor(() -> e.getValue().equals(nodes[replica].server().store().get(e.getKey()))));
        }
    }

    private String keyOwnedBy(int index) {
        for (int i = 0; ; i++) {
            if (client.router().primaryIndex("key" + i) == index) {
                return "key" + i;
            }
        }
    }

    private static String sendLine(int port, String line) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** Chon N port client con trong, ma port + 1000 (kenh replication) cung con trong. */
    private static int[] freeClientPorts(int n) throws IOException {
        int[] ports = new int[n];
        int found = 0;
        for (int candidate = 20000 + (int) (Math.random() * 20000); found < n; candidate += 7) {
            if (isFree(candidate) && isFree(candidate + ServerConfig.REPLICATION_PORT_OFFSET)) {
                ports[found++] = candidate;
            }
        }
        return ports;
    }

    private static boolean isFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
