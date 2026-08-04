package ai.chat2db.plugin.generic;

import ai.chat2db.plugin.generic.identifier.GenericIdentifierProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenericIdentifierProcessorTest {

    @Test
    void escapeStringDoublesSingleQuotes() {
        assertNull(GenericIdentifierProcessor.INSTANCE.escapeString(null));
        assertEquals("plain", GenericIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertEquals("a''b", GenericIdentifierProcessor.INSTANCE.escapeString("a'b"));
        assertEquals("''; DROP TABLE x; --",
                GenericIdentifierProcessor.INSTANCE.escapeString("'; DROP TABLE x; --"));
    }

    @Test
    void quoteIdentifierDoublesDialectQuoteChar() {
        // DuckDB-style double quotes
        assertEquals("\"plain\"", GenericIdentifierProcessor.quoteIdentifier("plain", '"'));
        assertEquals("\"a\"\"b\"", GenericIdentifierProcessor.quoteIdentifier("a\"b", '"'));
        assertEquals("\"we\"\"\"\"ird\"", GenericIdentifierProcessor.quoteIdentifier("\"we\"\"ird\"", '"'));
        // TDengine-style backticks
        assertEquals("`a``b`", GenericIdentifierProcessor.quoteIdentifier("a`b", '`'));
        assertEquals("`a``; DROP TABLE b; --`",
                GenericIdentifierProcessor.quoteIdentifier("a`; DROP TABLE b; --", '`'));
    }

    @Test
    void quoteIdentifierIsConditionalForSpiConsumers() {
        // null/blank pass through
        assertNull(GenericIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", GenericIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals(" ", GenericIdentifierProcessor.INSTANCE.quoteIdentifier(" "));
        // valid plain identifiers are returned unquoted
        assertEquals("plain", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("Plain_Case1", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("Plain_Case1"));
        // anything else is wrapped with embedded quotes doubled
        assertEquals("\"a\"\"b\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("a\"b"));
        assertEquals("\"with space\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("with space"));
        assertEquals("\"1abc\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("1abc"));
        // versioned overload delegates to the conditional variant
        assertEquals("plain", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("plain", null, null));
        assertEquals("\"a\"\"b\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifier("a\"b", null, null));
    }

    @Test
    void quoteIdentifierAlwaysWrapsUnconditionally() {
        assertNull(GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("\"\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
        assertEquals("\" \"", GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways(" "));
        assertEquals("\"plain\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("\"a\"\"b\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways("a\"b"));
        assertEquals("\"\"\"we\"\"\"\"ird\"\"\"",
                GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"we\"\"ird\""));
    }

    @Test
    void alwaysQuoteAndRemoveRoundTripRawIdentifiers() {
        for (String raw : new String[] {"", " ", "plain", "\"", "\"edge", "edge\"", "\"quoted\"", "a\"\"b"}) {
            assertEquals(raw, GenericIdentifierProcessor.INSTANCE.removeIdentifierQuote(
                    GenericIdentifierProcessor.INSTANCE.quoteIdentifierAlways(raw)), raw);
        }
    }

    @Test
    void quoteIdentifierIgnoreCaseIsTheAlwaysQuoteVariant() {
        assertNull(GenericIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
        assertEquals("\"plain\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
        assertEquals("\"a\"\"b\"", GenericIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("a\"b"));
    }

    @Test
    void escapeIdentifierStripsSurroundingPairAndDoublesEmbeddedQuotes() {
        assertEquals("a\"\"b", GenericIdentifierProcessor.escapeIdentifier("a\"b"));
        assertEquals("we\"\"\"\"ird", GenericIdentifierProcessor.escapeIdentifier("\"we\"\"ird\""));
        assertEquals("a``b", GenericIdentifierProcessor.escapeIdentifier("a`b", '`'));
        assertEquals("", GenericIdentifierProcessor.escapeIdentifier(null));
    }
}
