package com.nhom05.cache.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HashUtilTest {

    /** Dung |hash| mod n tinh bang long (khong tran so) de doi chieu voi cong thuc dac ta. */
    private static int expected(String key, int n) {
        return (int) (Math.abs((long) key.hashCode()) % n);
    }

    @Test
    void matchesSpecFormula() {
        for (String key : new String[]{"username", "a", "session:abc123", "Nguyễn Tiến Đức", ""}) {
            for (int n = 1; n <= 5; n++) {
                assertEquals(expected(key, n), HashUtil.computeServerIndex(key, n), key + " n=" + n);
            }
        }
    }

    @Test
    void keyWithMinValueHashCodeStillGivesValidIndex() {
        String key = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, key.hashCode());
        for (int n = 1; n <= 7; n++) {
            int idx = HashUtil.computeServerIndex(key, n);
            assertTrue(idx >= 0 && idx < n, "n=" + n + " -> " + idx);
            assertEquals(expected(key, n), idx);
        }
    }

    @Test
    void replicaIsNextServerWrappingAround() {
        assertEquals(1, HashUtil.replicaIndex(0, 3));
        assertEquals(2, HashUtil.replicaIndex(1, 3));
        assertEquals(0, HashUtil.replicaIndex(2, 3));
        assertEquals(0, HashUtil.replicaIndex(0, 1));
    }

    @Test
    void rejectsNonPositiveServerCount() {
        assertThrows(IllegalArgumentException.class, () -> HashUtil.computeServerIndex("k", 0));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.replicaIndex(0, -1));
    }
}
