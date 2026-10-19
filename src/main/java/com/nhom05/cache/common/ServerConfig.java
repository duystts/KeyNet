package com.nhom05.cache.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Doc config.properties dung chung cho ca he thong (muc 4 dac ta).
 *
 *   server.<i>.host / server.<i>.port   danh sach Cache Server, i danh tu 0, lien tuc
 *   cache.serverIndex                   server cua instance dang chay (mac dinh 0)
 *   cache.ttlSeconds                    thoi gian song cua key (mac dinh 60)
 *   cache.maxEntries                    so key toi da moi server (mac dinh 1000)
 *   registry.host / registry.port       dia chi Registry (mac dinh 127.0.0.1:6000)
 */
public final class ServerConfig {

    /** Port replication = port client + 1000 (muc 5 dac ta). */
    public static final int REPLICATION_PORT_OFFSET = 1000;

    public record ServerAddress(String host, int port) {
    }

    private final List<ServerAddress> servers;
    private final int serverIndex;
    private final long ttlSeconds;
    private final int maxEntries;
    private final ServerAddress registry;

    private ServerConfig(List<ServerAddress> servers, int serverIndex, long ttlSeconds, int maxEntries,
                         ServerAddress registry) {
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("Chua khai bao server nao (server.0.host / server.0.port)");
        }
        if (serverIndex < 0 || serverIndex >= servers.size()) {
            throw new IllegalArgumentException("serverIndex=" + serverIndex + " nam ngoai danh sach "
                    + servers.size() + " server");
        }
        if (ttlSeconds <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("cache.ttlSeconds va cache.maxEntries phai > 0");
        }
        checkPortsDistinct(servers, registry);
        this.servers = List.copyOf(servers);
        this.serverIndex = serverIndex;
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
        this.registry = registry;
    }

    /** Doc file cau hinh (UTF-8). */
    public static ServerConfig load(String path) throws IOException {
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
            props.load(in);
        }
        return fromProperties(props);
    }

    static ServerConfig fromProperties(Properties props) {
        List<ServerAddress> servers = new ArrayList<>();
        for (int i = 0; props.containsKey("server." + i + ".host"); i++) {
            String host = props.getProperty("server." + i + ".host").strip();
            int port = intProp(props, "server." + i + ".port", null);
            servers.add(new ServerAddress(host, port));
        }
        ServerAddress registry = new ServerAddress(
                props.getProperty("registry.host", "127.0.0.1").strip(),
                intProp(props, "registry.port", "6000"));
        return new ServerConfig(servers,
                intProp(props, "cache.serverIndex", "0"),
                intProp(props, "cache.ttlSeconds", "60"),
                intProp(props, "cache.maxEntries", "1000"),
                registry);
    }

    /** Tao ServerConfig truc tiep tu danh sach dia chi (dung cho test va lap trinh). */
    public static ServerConfig of(List<ServerAddress> servers, int serverIndex) {
        return new ServerConfig(servers, serverIndex, 60, 1000, new ServerAddress("127.0.0.1", 6000));
    }

    public static ServerConfig of(List<ServerAddress> servers, int serverIndex, long ttlSeconds, int maxEntries) {
        return new ServerConfig(servers, serverIndex, ttlSeconds, maxEntries, new ServerAddress("127.0.0.1", 6000));
    }

    /**
     * Moi server dung 2 cong (client va client + 1000), cung may thi khong duoc trung nhau
     * va khong trung cong Registry. Vd server.0.port=5000 -> cong replication 6000 trung Registry:
     * server van chay nhung heartbeat / replication hong am tham.
     */
    private static void checkPortsDistinct(List<ServerAddress> servers, ServerAddress registry) {
        Map<String, String> used = new HashMap<>();
        if (registry != null) {
            claim(used, registry.host(), registry.port(), "registry.port");
        }
        for (int i = 0; i < servers.size(); i++) {
            ServerAddress s = servers.get(i);
            claim(used, s.host(), s.port(), "server." + i + ".port");
            claim(used, s.host(), s.port() + REPLICATION_PORT_OFFSET, "cong replication cua server." + i);
        }
    }

    private static void claim(Map<String, String> used, String host, int port, String owner) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(owner + "=" + port + " nam ngoai 1..65535");
        }
        String previous = used.putIfAbsent(host + ":" + port, owner);
        if (previous != null) {
            throw new IllegalArgumentException("Cong " + host + ":" + port + " bi trung: "
                    + previous + " va " + owner);
        }
    }

    private static int intProp(Properties props, String name, String defaultValue) {
        String raw = props.getProperty(name, defaultValue);
        if (raw == null) {
            throw new IllegalArgumentException("Thieu cau hinh " + name);
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cau hinh " + name + " khong phai so: " + raw);
        }
    }

    /**
     * Ban sao voi serverIndex khac, de chay nhieu instance tu cung 1 file config
     * (vd: java -jar keynet.jar config/config.properties 1).
     */
    public ServerConfig withServerIndex(int index) {
        return new ServerConfig(servers, index, ttlSeconds, maxEntries, registry);
    }

    public List<ServerAddress> servers() {
        return servers;
    }

    public int serverCount() {
        return servers.size();
    }

    public int serverIndex() {
        return serverIndex;
    }

    /** Dia chi cua server dang chay (servers[serverIndex]). */
    public ServerAddress self() {
        return servers.get(serverIndex);
    }

    /** Port kenh replication cua server thu index (muc 5 dac ta): port client + 1000. */
    public int replicationPort(int index) {
        return servers.get(index).port() + REPLICATION_PORT_OFFSET;
    }

    /** Server du phong cua server dang chay: (serverIndex + 1) mod N. */
    public int backupIndex() {
        return (serverIndex + 1) % servers.size();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public ServerAddress registry() {
        return registry;
    }
}
