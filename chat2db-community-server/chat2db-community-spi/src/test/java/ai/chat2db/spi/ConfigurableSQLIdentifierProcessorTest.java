package ai.chat2db.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableSQLIdentifierProcessorTest {

    @Test
    void parsesSingleQuoteSpec() {
        ConfigurableSQLIdentifierProcessor processor = ConfigurableSQLIdentifierProcessor.fromSpec("`");

        assertEquals("`", processor.getOpen());
        assertEquals("`", processor.getClose());
    }

    @Test
    void parsesOpenClosePairSpec() {
        ConfigurableSQLIdentifierProcessor processor = ConfigurableSQLIdentifierProcessor.fromSpec("[:]");

        assertEquals("[", processor.getOpen());
        assertEquals("]", processor.getClose());
    }

    @Test
    void rejectsBlankAndMalformedSpecs() {
        assertNull(ConfigurableSQLIdentifierProcessor.fromSpec(null));
        assertNull(ConfigurableSQLIdentifierProcessor.fromSpec("  "));
        assertNull(ConfigurableSQLIdentifierProcessor.fromSpec(":]"));
        assertNull(ConfigurableSQLIdentifierProcessor.fromSpec("[:"));
    }

    @Test
    void leavesPlainIdentifiersUnquoted() {
        assertEquals("orders",
                ConfigurableSQLIdentifierProcessor.fromSpec("`").quoteIdentifier("orders"));
    }

    @Test
    void quotesSpecialIdentifiersAndEscapesEmbeddedCloseQuote() {
        ConfigurableSQLIdentifierProcessor backtick = ConfigurableSQLIdentifierProcessor.fromSpec("`");
        assertEquals("`order table`", backtick.quoteIdentifier("order table"));
        assertEquals("`a``b`", backtick.quoteIdentifier("a`b"));

        ConfigurableSQLIdentifierProcessor bracket = ConfigurableSQLIdentifierProcessor.fromSpec("[:]");
        assertEquals("[order table]", bracket.quoteIdentifier("order table"));
        assertEquals("[a]]b]", bracket.quoteIdentifier("a]b"));
    }

    @Test
    void versionedAndIgnoreCaseVariantsDelegate() {
        ConfigurableSQLIdentifierProcessor processor = ConfigurableSQLIdentifierProcessor.fromSpec("`");

        assertEquals("`order table`", processor.quoteIdentifier("order table", 8, 0));
        assertEquals("`order table`", processor.quoteIdentifierIgnoreCase("order table"));
    }

    @Test
    void detectsAndRemovesQuotesRoundTrip() {
        ConfigurableSQLIdentifierProcessor processor = ConfigurableSQLIdentifierProcessor.fromSpec("`");
        String quoted = processor.quoteIdentifier("a`b c");

        assertTrue(processor.isQuoteIdentifier(quoted));
        assertFalse(processor.isQuoteIdentifier("plain"));
        assertEquals("a`b c", processor.removeIdentifierQuote(quoted));
        assertEquals("plain", processor.removeIdentifierQuote("plain"));
    }
}
