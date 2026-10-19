package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientPanelTest {

    @BeforeAll
    static void initHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void testClientPanelCreation() {
        List<ServerConfig.ServerAddress> servers = List.of(
                new ServerConfig.ServerAddress("127.0.0.1", 5001),
                new ServerConfig.ServerAddress("127.0.0.1", 5002),
                new ServerConfig.ServerAddress("127.0.0.1", 5003)
        );
        CacheClient client = new CacheClient(servers);
        ClientHistoryModel historyModel = new ClientHistoryModel();

        ClientPanel panel = new ClientPanel(client, historyModel);
        assertNotNull(panel);

        // Them du lieu mau vao model de verify bang khong loi
        historyModel.putKey("testKey", "testVal", 0, 1);
        historyModel.logCommand("PUT", "testKey", "testVal", 0, false, "OK");

        assertEquals(1, historyModel.keyCount());
        assertEquals(1, historyModel.allCommandLogs().size());
    }
}
