package ai.chat2db.plugin.mysql.identifier;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract tests for {@link MysqlIdentifierProcessor}.
 * Covers the backtick-based {@code quoteIdentifierAlways} /
 * {@code removeIdentifierQuote} round-trip and embedded-delimiter escaping.
 */
class MysqlIdentifierProcessorTest {

    private final MysqlIdentifierProcessor processor = new MysqlIdentifierProcessor();

    @Test
    void quoteIdentifierAlways_wrapsInBacktick() {
        assertEquals("`mycol`", processor.quoteIdentifierAlways("mycol"));
    }

    @Test
    void quoteIdentifierAlways_escapesEmbeddedBacktick() {
        assertEquals("`a``b`", processor.quoteIdentifierAlways("a`b"));
    }

    @Test
    void quoteIdentifierAlways_preservesMixedCase() {
        assertEquals("`MixedCase`", processor.quoteIdentifierAlways("MixedCase"));
    }

    @Test
    void quoteIdentifierAlways_handlesNull() {
        assertNull(processor.quoteIdentifierAlways(null));
    }

    @Test
    void removeIdentifierQuote_stripsBacktick() {
        assertEquals("mycol", processor.removeIdentifierQuote("`mycol`"));
    }

    @Test
    void removeIdentifierQuote_unescapesBacktick() {
        assertEquals("a`b", processor.removeIdentifierQuote("`a``b`"));
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
    void roundTrip_embeddedBacktick() {
        String raw = "a`b";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }

    @Test
    void roundTrip_mixedCase() {
        String raw = "MixedCase";
        assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
    }
}
