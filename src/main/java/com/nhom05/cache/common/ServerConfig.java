package com.nhom05.cache.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
