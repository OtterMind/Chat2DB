package ai.chat2db.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contract tests for {@link DefaultSQLIdentifierProcessor}.
 * Covers the {@code quoteIdentifierAlways} / {@code removeIdentifierQuote}
 * round-trip, conditional vs always-quote behavior, and embedded-delimiter
 * escaping.
 */
class DefaultSQLIdentifierProcessorTest {

    private final DefaultSQLIdentifierProcessor processor = new DefaultSQLIdentifierProcessor();

    // ---- quoteIdentifierAlways ----

    @Test
    void quoteIdentifierAlways_wrapsSimpleName() {
        assertEquals("\"mycol\"", processor.quoteIdentifierAlways("mycol"));
    }

    @Test
    void quoteIdentifierAlways_preservesMixedCase() {
        assertEquals("\"MixedCase\"", processor.quoteIdentifierAlways("MixedCase"));
    }

    @Test
    void quoteIdentifierAlways_escapesEmbeddedDoubleQuote() {
        assertEquals("\"A\"\"B\"", processor.quoteIdentifierAlways("A\"B"));
    }

    @Test
    void quoteIdentifierAlways_handlesReservedKeyword() {
        assertEquals("\"SELECT\"", processor.quoteIdentifierAlways("SELECT"));
    }

    @Test
    void quoteIdentifierAlways_handlesNull() {
        assertNull(processor.quoteIdentifierAlways(null));
    }

    // ---- quoteIdentifier (conditional) ----

    @Test
    void quoteIdentifier_doesNotQuoteLowercaseValid() {
        assertEquals("lower", processor.quoteIdentifier("lower"));
    }

    @Test
    void quoteIdentifier_quotesInvalidIdentifier() {
        assertEquals("\"a b\"", processor.quoteIdentifier("a b"));
    }

    @Test
    void quoteIdentifierIgnoreCase_doesNotQuoteLowercase() {
        assertEquals("lower", processor.quoteIdentifierIgnoreCase("lower"));
    }

    // ---- removeIdentifierQuote ----

    @Test
    void removeIdentifierQuote_stripsAndUnescapes() {
        assertEquals("A\"B", processor.removeIdentifierQuote("\"A\"\"B\""));
    }

    @Test
    void removeIdentifierQuote_passesThroughUnquoted() {
        assertEquals("plain", processor.removeIdentifierQuote("plain"));
    }

    @Test
    void removeIdentifierQuote_stripsSimpleQuoted() {
        assertEquals("mycol", processor.removeIdentifierQuote("\"mycol\""));
    }

    // ---- round-trip ----

    @Test
    void roundTrip_plainName() {
        String raw = "mycol";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_mixedCase() {
        String raw = "MixedCase";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_embeddedDelimiter() {
        String raw = "A\"B";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_reservedKeyword() {
        String raw = "SELECT";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_empty() {
        String raw = "";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void legacyConditionalImplementationFailsFastInsteadOfReturningUnquotedIdentifier() {
        ISQLIdentifierProcessor legacyProcessor = new LegacyConditionalIdentifierProcessor();

        assertThrows(UnsupportedOperationException.class,
                () -> legacyProcessor.quoteIdentifierAlways("plain"));
    }

    private static final class LegacyConditionalIdentifierProcessor implements ISQLIdentifierProcessor {
        @Override
        public boolean isValidIdentifier(String identifier) {
            return true;
        }

        @Override
        public boolean isReservedKeyword(String identifier, Integer majorVersion, Integer minorVersion) {
            return false;
        }

        @Override
        public String quoteIdentifier(String identifier, Integer majorVersion, Integer minorVersion) {
            return identifier;
        }

        @Override
        public String quoteIdentifier(String identifier) {
            return identifier;
        }

        @Override
        public String removeIdentifierQuote(String identifier) {
            return identifier;
        }

        @Override
        public String quoteIdentifierIgnoreCase(String identifier) {
            return identifier;
        }

        @Override
        public boolean isQuoteIdentifier(String identifier) {
            return false;
        }

        @Override
        public String convertIdentifierCase(String identifier) {
            return identifier;
        }

        @Override
        public String escapeString(String str) {
            return str;
        }
    }
}
