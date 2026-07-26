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
}
