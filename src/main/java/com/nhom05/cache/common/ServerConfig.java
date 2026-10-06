package com.nhom05.cache.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Doc config.properties dung chung cho ca he thong (muc 4 trong dac ta giao thuc).
 *
 * File config.properties gom 2 phan:
 *  1) Danh sach toan bo Cache Server (dung cho Module 2 - Client hashing,
 *     va Module 3 - tim server du phong):
 *       server.0.host=127.0.0.1
 *       server.0.port=5001
 *       server.1.host=127.0.0.1
 *       server.1.port=5002
 *       ...
 *  2) Cau hinh rieng cua mot the hien Cache Server dang chay (Module 1):
 *       cache.serverIndex=0
 *       cache.ttlSeconds=60
 *       cache.maxEntries=1000
 */
public final class ServerConfig {

    public record ServerAddress(String host, int port) {
    }

    private final List<ServerAddress> servers;
    private final int serverIndex;
    private final long ttlSeconds;
    private final int maxEntries;

    private ServerConfig(List<ServerAddress> servers, int serverIndex, long ttlSeconds, int maxEntries) {
        this.servers = servers;
        this.serverIndex = serverIndex;
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
    }

    public static ServerConfig load(String path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            props.load(in);
        }
        return fromProperties(props);
    }

    static ServerConfig fromProperties(Properties props) {
        List<ServerAddress> servers = new ArrayList<>();
        int i = 0;
        while (props.containsKey("server." + i + ".host")) {
            String host = props.getProperty("server." + i + ".host");
            int port = Integer.parseInt(props.getProperty("server." + i + ".port"));
            servers.add(new ServerAddress(host, port));
            i++;
        }

        int serverIndex = Integer.parseInt(props.getProperty("cache.serverIndex", "0"));
        long ttlSeconds = Long.parseLong(props.getProperty("cache.ttlSeconds", "60"));
        int maxEntries = Integer.parseInt(props.getProperty("cache.maxEntries", "1000"));

        return new ServerConfig(servers, serverIndex, ttlSeconds, maxEntries);
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

    /**
     * Port rieng cho kenh replication giua cac server (muc 5 dac ta: "port rieng,
     * khac port phuc vu client"). Quy uoc: port phuc vu client + 1000.
     */
    public static final int REPLICATION_PORT_OFFSET = 1000;

    public int replicationPort(int index) {
        return servers.get(index).port() + REPLICATION_PORT_OFFSET;
    }

    /** Chi so server du phong cua server nay: (serverIndex + 1) mod N. */
    public int backupIndex() {
        return (serverIndex + 1) % servers.size();
    }

    public ServerAddress self() {
        return servers.get(serverIndex);
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public int maxEntries() {
        return maxEntries;
    }
}
