package ai.chat2db.community.tools.security.secretimport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SecretImportSafetyTest {

    @Test
    void safeIdStripsControlCharactersAndNewlines() {
        String dirty = "ab\n\rcd\tef\u0000ghijkl";
        String safe = SecretImportSafety.safeId(dirty);
        assertFalse(safe.contains("\n"));
        assertFalse(safe.contains("\r"));
        assertFalse(safe.contains("\t"));
        assertFalse(safe.contains("\u0000"));
        assertEquals(8, safe.length());
        assertEquals("abcdefgh", safe);
    }

    @Test
    void safeIdHandlesNullAndEmpty() {
        assertEquals("-", SecretImportSafety.safeId(null));
        assertEquals("-", SecretImportSafety.safeId(""));
        assertEquals("-", SecretImportSafety.safeId("\n\r\t"));
    }
}
