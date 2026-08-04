package ai.chat2db.plugin.sqlserver.identifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract tests for {@link SqlServerIdentifierProcessor}.
 * Covers the bracket-based {@code quoteIdentifierAlways} /
 * {@code removeIdentifierQuote} round-trip and embedded-delimiter escaping.
 */
class SqlServerIdentifierProcessorTest {

    private final SqlServerIdentifierProcessor processor = new SqlServerIdentifierProcessor();

    @Test
    void quoteIdentifierAlways_wrapsInBrackets() {
        assertEquals("[mycol]", processor.quoteIdentifierAlways("mycol"));
    }

    @Test
    void quoteIdentifierAlways_preservesMixedCase() {
        assertEquals("[MixedCase]", processor.quoteIdentifierAlways("MixedCase"));
    }

    @Test
    void quoteIdentifierAlways_escapesEmbeddedCloseBracket() {
        assertEquals("[a]]b]", processor.quoteIdentifierAlways("a]b"));
    }

    @Test
    void quoteIdentifierAlways_handlesNull() {
        assertNull(processor.quoteIdentifierAlways(null));
    }

    @Test
    void removeIdentifierQuote_stripsBrackets() {
        assertEquals("mycol", processor.removeIdentifierQuote("[mycol]"));
    }

    @Test
    void removeIdentifierQuote_unescapesCloseBracket() {
        assertEquals("a]b", processor.removeIdentifierQuote("[a]]b]"));
    }

    @Test
    void removeIdentifierQuote_stripsDoubleQuote() {
        assertEquals("mycol", processor.removeIdentifierQuote("\"mycol\""));
    }

    @Test
    void roundTrip_plainName() {
        String raw = "mycol";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_embeddedCloseBracket() {
        String raw = "a]b";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_mixedCase() {
        String raw = "MixedCase";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }
}
