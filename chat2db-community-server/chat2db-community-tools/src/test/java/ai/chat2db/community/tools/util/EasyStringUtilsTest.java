package ai.chat2db.community.tools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract coverage for {@link EasyStringUtils#escapeAndQuoteString(String)},
 * which the Excel/CSV import path ({@code ReadHeaderListener}) now relies on
 * to safely embed cell values into INSERT statements. Confirms that single
 * quotes (and backslashes) are escaped so a value such as {@code O'Brien}
 * cannot break or inject into the generated SQL.
 */
class EasyStringUtilsTest {

    @Test
    void escapeAndQuoteStringDoublesSingleQuotes() {
        assertEquals("'O''Brien'", EasyStringUtils.escapeAndQuoteString("O'Brien"));
    }

    @Test
    void escapeAndQuoteStringWrapsPlainValue() {
        assertEquals("'plain'", EasyStringUtils.escapeAndQuoteString("plain"));
    }

    @Test
    void escapeAndQuoteStringDoublesBackslashes() {
        assertEquals("'a\\\\b'", EasyStringUtils.escapeAndQuoteString("a\\b"));
    }

    /**
     * Regression: getBitString must pad each byte to 8 bits before
     * concatenating, otherwise multi-byte BIT values are corrupted
     * (e.g. {0x01, 0x02} used to become "110" instead of "0000000100000010").
     */
    @Test
    void getBitStringPadsEachByteToEightBits() {
        assertEquals("0000000100000010", EasyStringUtils.getBitString(new byte[] {0x01, 0x02}, 16));
    }

    @Test
    void getBitStringSingleByte() {
        assertEquals("00000011", EasyStringUtils.getBitString(new byte[] {0x03}, 8));
    }

    @Test
    void getBitStringReturnsRightmostRequestedBits() {
        byte[] bytes = {0x01, 0x02};
        assertEquals("0", EasyStringUtils.getBitString(bytes, 1));
        assertEquals("0000010", EasyStringUtils.getBitString(bytes, 7));
        assertEquals("100000010", EasyStringUtils.getBitString(bytes, 9));
        assertEquals("000000100000010", EasyStringUtils.getBitString(bytes, 15));
    }

    /**
     * Regression: cutName must treat workNo as a literal prefix, not a regex
     * (previously RegExUtils.removeFirst interpreted metacharacters).
     */
    @Test
    void cutNameTreatsWorkNoAsLiteral() {
        // "a.b" as regex would match "axb"; as a literal prefix it does not match
        assertEquals("axb", EasyStringUtils.cutName("axb", "a.b"));
    }

    @Test
    void cutNameRemovesLiteralPrefixAndTrailingZeros() {
        assertEquals("name", EasyStringUtils.cutName("WK001name00", "WK001"));
    }

    @Test
    void cutNameDoesNotRemoveAnInternalWorkNumber() {
        assertEquals("prefixWK001name", EasyStringUtils.cutName("prefixWK001name00", "WK001"));
    }
}
