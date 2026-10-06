package com.nhom05.cache.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HashUtilTest {

    @Test
    void keyWithMinValueHashCodeGivesValidIndex() {
        String key = "polygenelubricants"; // "polygenelubricants".hashCode() == Integer.MIN_VALUE
        assertEquals(Integer.MIN_VALUE, key.hashCode());
        for (int n = 1; n <= 7; n++) {
            int idx = HashUtil.computeServerIndex(key, n);
            assertTrue(idx >= 0 && idx < n, "n=" + n + " -> " + idx);
            // |hash| mod n theo dung dac ta (tinh bang long de khong tran so)
            assertEquals((int) (Math.abs((long) key.hashCode()) % n), idx);
        }
    }

    @Test
    void matchesSpecFormulaForOrdinaryKeys() {
        for (String key : new String[]{"username", "a", "session:abc123", "Nguyễn Tiến Đức", ""}) {
            for (int n = 1; n <= 5; n++) {
                assertEquals((int) (Math.abs((long) key.hashCode()) % n), HashUtil.computeServerIndex(key, n));
            }
        }
    }

    @Test
    void replicaIsNextServerWrappingAround() {
        assertEquals(1, HashUtil.replicaIndex(0, 3));
        assertEquals(0, HashUtil.replicaIndex(2, 3));
        assertEquals(0, HashUtil.replicaIndex(0, 1));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.computeServerIndex("k", 0));
    }
}
