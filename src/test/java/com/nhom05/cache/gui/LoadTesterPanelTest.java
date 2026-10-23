package com.nhom05.cache.gui;

import com.nhom05.cache.client.CacheClient;
import com.nhom05.cache.common.ServerConfig.ServerAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test cho giao dien LoadTesterPanel (Issue #17).
 */
class LoadTesterPanelTest {

    @BeforeAll
    static void initHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void testPanelComponentsInitialization() {
        CacheClient client = new CacheClient(List.of(
                new ServerAddress("127.0.0.1", 5001),
                new ServerAddress("127.0.0.1", 5002)
        ));

        LoadTesterPanel panel = new LoadTesterPanel(client);

        assertNotNull(panel);
        assertNotNull(panel.btnStart());
        assertNotNull(panel.btnStop());
        assertNotNull(panel.progressBar());
        assertNotNull(panel.txtLogArea());
        assertNotNull(panel.spinnerThreads());
        assertNotNull(panel.spinnerRequestsPerThread());

        assertTrue(panel.btnStart().isEnabled());
        assertFalse(panel.btnStop().isEnabled());
        assertEquals(10, panel.spinnerThreads().getValue());
        assertEquals(50, panel.spinnerRequestsPerThread().getValue());
        assertNotNull(panel.cmbTester());
        assertNotNull(panel.chkUsePool());
        assertTrue(panel.chkUsePool().isSelected());
        assertEquals("Nguyen Van Nhat (MSSV: 2380601556)", panel.cmbTester().getSelectedItem());
    }

    @Test
    void testFrameCreation() {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            CacheClient client = new CacheClient(List.of(
                    new ServerAddress("127.0.0.1", 5001)
            ));

            LoadTesterFrame frame = new LoadTesterFrame(client);
            assertNotNull(frame);
            assertNotNull(frame.loadTesterPanel());
            frame.dispose();
        }
    }
}
