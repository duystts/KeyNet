package com.nhom05.cache.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    private static Properties threeServers() {
        Properties p = new Properties();
        for (int i = 0; i < 3; i++) {
            p.setProperty("server." + i + ".host", "127.0.0.1");
            p.setProperty("server." + i + ".port", String.valueOf(5001 + i));
        }
        return p;
    }

    @Test
    void readsServersAndAppliesDefaults() {
        ServerConfig c = ServerConfig.fromProperties(threeServers());
        assertEquals(3, c.serverCount());
        assertEquals(new ServerConfig.ServerAddress("127.0.0.1", 5002), c.servers().get(1));
        assertEquals(0, c.serverIndex());
        assertEquals(60, c.ttlSeconds());
        assertEquals(1000, c.maxEntries());
        assertEquals(new ServerConfig.ServerAddress("127.0.0.1", 6000), c.registry());
    }

    @Test
    void readsCustomValues() {
        Properties p = threeServers();
        p.setProperty("cache.serverIndex", "2");
        p.setProperty("cache.ttlSeconds", "30");
        p.setProperty("cache.maxEntries", "500");
        p.setProperty("registry.host", "10.0.0.5");
        p.setProperty("registry.port", "7000");
        ServerConfig c = ServerConfig.fromProperties(p);
        assertEquals(5003, c.self().port());
        assertEquals(30, c.ttlSeconds());
        assertEquals(500, c.maxEntries());
        assertEquals(new ServerConfig.ServerAddress("10.0.0.5", 7000), c.registry());
    }

    @Test
    void withServerIndexKeepsEverythingElse() {
        ServerConfig c = ServerConfig.fromProperties(threeServers()).withServerIndex(1);
        assertEquals(1, c.serverIndex());
        assertEquals(5002, c.self().port());
        assertEquals(3, c.serverCount());
    }

    @Test
    void stopsAtFirstGapInServerIndexes() {
        Properties p = threeServers();
        p.remove("server.1.host");
        p.remove("server.1.port");
        assertEquals(1, ServerConfig.fromProperties(p).serverCount(), "index phai lien tuc tu 0");
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(new Properties()));
        ServerConfig c = ServerConfig.fromProperties(threeServers());
        assertThrows(IllegalArgumentException.class, () -> c.withServerIndex(3));
        assertThrows(IllegalArgumentException.class, () -> c.withServerIndex(-1));

        Properties badPort = threeServers();
        badPort.setProperty("server.0.port", "abc");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(badPort));

        Properties missingPort = threeServers();
        missingPort.remove("server.2.port");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(missingPort));
    }

    @Test
    void loadsTheRepoConfigFile() throws IOException {
        ServerConfig c = ServerConfig.load("config/config.properties");
        assertEquals(3, c.serverCount());
        assertEquals(5001, c.servers().get(0).port());
        assertEquals(6000, c.registry().port());
    }
}
