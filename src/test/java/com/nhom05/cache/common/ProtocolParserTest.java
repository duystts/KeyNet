package com.nhom05.cache.common;

import com.nhom05.cache.common.ProtocolParser.CommandType;
import com.nhom05.cache.common.ProtocolParser.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolParserTest {

    @Test
    void parsesValidCommands() {
        assertEquals(new Request(CommandType.PUT, "username", "Duy"), ProtocolParser.parseRequest("PUT|username|Duy"));
        assertEquals(new Request(CommandType.GET, "username", null), ProtocolParser.parseRequest("GET|username"));
        assertEquals(new Request(CommandType.DELETE, "username", null), ProtocolParser.parseRequest("DELETE|username"));
    }

    @Test
    void commandNameIsCaseInsensitiveAndLineIsTrimmed() {
        assertEquals(CommandType.PUT, ProtocolParser.parseRequest("put|k|v").type());
        assertEquals(CommandType.GET, ProtocolParser.parseRequest("Get|k").type());
        // telnet gui "\r\n"
        assertEquals(new Request(CommandType.GET, "k", null), ProtocolParser.parseRequest("GET|k\r"));
    }

    @Test
    void wrongFieldCountOrUnknownCommandIsUnknown() {
        for (String bad : new String[]{null, "", "   ", "PUT|k", "PUT|k|v|x", "GET", "GET|k|x", "DELETE", "FOO|k", "HELLO"}) {
            assertEquals(CommandType.UNKNOWN, ProtocolParser.parseRequest(bad).type(), "phai la UNKNOWN: " + bad);
        }
    }

    @Test
    void emptyValueIsAllowed() {
        Request r = ProtocolParser.parseRequest("PUT|k|");
        assertEquals(CommandType.PUT, r.type());
        assertEquals("", r.value());
        assertTrue(ProtocolParser.isValueValid(r.value()));
    }

    @Test
    void vietnameseValueIsKept() {
        assertEquals("Nguyễn Tiến Đức", ProtocolParser.parseRequest("PUT|ten|Nguyễn Tiến Đức").value());
    }

    @Test
    void keyValidation() {
        assertTrue(ProtocolParser.isKeyValid("username"));
        assertTrue(ProtocolParser.isKeyValid("k".repeat(256)));
        assertFalse(ProtocolParser.isKeyValid("k".repeat(257)));
        assertFalse(ProtocolParser.isKeyValid(""));
        assertFalse(ProtocolParser.isKeyValid(null));
        assertFalse(ProtocolParser.isKeyValid("a|b"));
        assertFalse(ProtocolParser.isKeyValid("a\nb"));
    }

    @Test
    void valueValidation() {
        assertTrue(ProtocolParser.isValueValid("v".repeat(4096)));
        assertFalse(ProtocolParser.isValueValid("v".repeat(4097)));
        assertFalse(ProtocolParser.isValueValid("a|b"));
        assertFalse(ProtocolParser.isValueValid(null));
    }

    @Test
    void buildsRequestsAndResponsesInSpecFormat() {
        assertEquals("PUT|k|v", ProtocolParser.put("k", "v"));
        assertEquals("GET|k", ProtocolParser.get("k"));
        assertEquals("DELETE|k", ProtocolParser.delete("k"));
        assertEquals("OK", ProtocolParser.ok());
        assertEquals("VALUE|Duy", ProtocolParser.value("Duy"));
        assertEquals("NOTFOUND", ProtocolParser.notFound());
        assertEquals("ERROR|E001|Invalid command syntax", ProtocolParser.error(ErrorCode.E001));
        assertEquals("ERROR|E004|Ca 2 server deu khong ket noi duoc", ProtocolParser.error(ErrorCode.E004, "Ca 2 server deu khong ket noi duoc"));
    }

    @Test
    void buildsReplicateLinesInSpecFormat() {
        assertEquals("REPLICATE|PUT|k|v|1712", ProtocolParser.replicatePut("k", "v", 1712L));
        assertEquals("REPLICATE|DELETE|k|1713", ProtocolParser.replicateDelete("k", 1713L));
    }

    @Test
    void parsesReplicatePutAndDelete() {
        ProtocolParser.Replicate put = ProtocolParser.parseReplicate("REPLICATE|PUT|k|v|42");
        assertNotNull(put);
        assertTrue(put.isPut());
        assertFalse(put.isDelete());
        assertEquals("k", put.key());
        assertEquals("v", put.value());
        assertEquals(42L, put.timestamp());

        ProtocolParser.Replicate del = ProtocolParser.parseReplicate("REPLICATE|DELETE|k|43");
        assertNotNull(del);
        assertTrue(del.isDelete());
        assertNull(del.value());
        assertEquals(43L, del.timestamp());
    }

    @Test
    void replicateWithWrongSyntaxOrNullIsRejected() {
        for (String bad : new String[]{null, "", "REPLICATE|PUT|k|v", "REPLICATE|PUT|k|v|abc",
                "REPLICATE|PUT|k|v|-1", "REPLICATE|DELETE|k", "REPLICATE|DELETE|k|1|2",
                "PUT|k|v|1", "REPLICATE|UPDATE|k|v|1"}) {
            assertNull(ProtocolParser.parseReplicate(bad), "phai tra null: " + bad);
        }
    }

    @Test
    void readsErrorCodeFromResponse() {
        assertEquals(ErrorCode.E004, ProtocolParser.errorCodeOf("ERROR|E004|Server overloaded"));
        assertEquals(ErrorCode.E006, ProtocolParser.errorCodeOf("ERROR|E006|Server syncing, retry later"));
        assertNull(ProtocolParser.errorCodeOf("OK"));
        assertNull(ProtocolParser.errorCodeOf("VALUE|ERROR|E004"));
        assertNull(ProtocolParser.errorCodeOf("ERROR|E999|la"));
        assertNull(ProtocolParser.errorCodeOf(null));
        assertTrue(ErrorCode.E004.shouldFailover());
        assertFalse(ErrorCode.E002.shouldFailover());
    }
}
