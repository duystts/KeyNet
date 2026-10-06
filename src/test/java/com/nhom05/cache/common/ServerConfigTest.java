package com.nhom05.cache.common;

import org.junit.jupiter.api.Test;

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
    void registryDefaultsTo6000WhenMissing() {
        ServerConfig c = ServerConfig.fromProperties(threeServers());
        assertEquals(new ServerConfig.ServerAddress("127.0.0.1", 6000), c.registry());
    }

    @Test
    void registryReadFromProperties() {
        Properties p = threeServers();
        p.setProperty("registry.host", "10.0.0.5");
        p.setProperty("registry.port", "7000");
        ServerConfig c = ServerConfig.fromProperties(p);
        assertEquals(new ServerConfig.ServerAddress("10.0.0.5", 7000), c.registry());
    }

    @Test
    void withServerIndexOverridesIndexAndKeepsRest() {
        ServerConfig c = ServerConfig.fromProperties(threeServers()).withServerIndex(2);
        assertEquals(2, c.serverIndex());
        assertEquals(5003, c.self().port());
        assertEquals(0, c.backupIndex());
        assertEquals(3, c.serverCount());
    }

    @Test
    void serverIndexOutOfRangeIsRejected() {
        ServerConfig c = ServerConfig.fromProperties(threeServers());
        assertThrows(IllegalArgumentException.class, () -> c.withServerIndex(3));
        assertThrows(IllegalArgumentException.class, () -> c.withServerIndex(-1));

        Properties p = threeServers();
        p.setProperty("cache.serverIndex", "5");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(p));
    }
}
