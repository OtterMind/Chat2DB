package ai.chat2db.plugin.redshift;

import ai.chat2db.plugin.redshift.identifier.RedshiftIdentifierProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftIdentifierProcessorTest {

    @Test
    void escapeSqlLiteralDoublesSingleQuotes() {
        assertEquals("o''brien", RedshiftIdentifierProcessor.INSTANCE.escapeString("o'brien"));
        assertEquals("plain", RedshiftIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertEquals("''''", RedshiftIdentifierProcessor.INSTANCE.escapeString("''"));
        assertNull(RedshiftIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void escapeIdentifierDoublesEmbeddedDoubleQuotes() {
        assertEquals("we\"\"ird", RedshiftIdentifierProcessor.escapeIdentifier("we\"ird"));
        assertEquals("plain", RedshiftIdentifierProcessor.escapeIdentifier("plain"));
        assertNull(RedshiftIdentifierProcessor.escapeIdentifier(null));
    }

    @Test
    void escapeIdentifierTreatsWrappingQuotesAsRawContent() {
        assertEquals("\"\"foo\"\"", RedshiftIdentifierProcessor.escapeIdentifier("\"foo\""));
        assertEquals("\"\"fo\"\"o\"\"", RedshiftIdentifierProcessor.escapeIdentifier("\"fo\"o\""));
    }

    @Test
    void quoteIdentifierReturnsPlainIdentifiersUnquoted() {
        assertEquals("foo", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("foo"));
        assertEquals("orders_2", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("orders_2"));
    }

    @Test
    void quoteIdentifierQuotesWhenNotPlain() {
        assertEquals("\"we\"\"ird\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("we\"ird"));
        assertEquals("\"has space\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("has space"));
        assertEquals("\"2leading\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("2leading"));
        assertEquals("\"MixedCase\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("MixedCase"));
        assertEquals("\"SELECT\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
    }

    @Test
    void quoteIdentifierRequotesAlreadyQuotedInput() {
        assertEquals("\"\"\"foo\"\"\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("\"foo\""));
    }

    @Test
    void quoteIdentifierPassesThroughNullAndBlank() {
        assertNull(RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier(""));
    }

    @Test
    void versionedQuoteIdentifierDelegatesToConditional() {
        assertEquals("foo", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("foo", null, null));
        assertEquals("\"has space\"",
                RedshiftIdentifierProcessor.INSTANCE.quoteIdentifier("has space", 1, 0));
    }

    @Test
    void quoteIdentifierIgnoreCaseUsesPostgreSqlConditionalRules() {
        assertEquals("foo", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("foo"));
        assertEquals("\"we\"\"ird\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("we\"ird"));
        assertEquals("", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(""));
        assertNull(RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
    }

    @Test
    void quoteIdentifierAlwaysWrapsAndEscapes() {
        assertEquals("\"foo\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways("foo"));
        assertEquals("\"\"\"foo\"\"\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"foo\""));
        assertEquals("\"we\"\"ird\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways("we\"ird"));
        assertEquals("\"\"", RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
        assertNull(RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
    }

    @Test
    void alwaysQuoteRoundTripsEveryRawIdentifierShape() {
        for (String raw : List.of("plain", "a\"b", "\"already\"", "\"", "a.b", "", " ")) {
            assertEquals(raw, RedshiftIdentifierProcessor.INSTANCE.removeIdentifierQuote(
                    RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(raw)), raw);
        }
    }

    @Test
    void maliciousSchemaNameIsNeutralizedInShowCreateTable() {
        String sql = RedshiftMetaData.buildShowCreateTableSql("public\"; DROP TABLE users; --", "t");
        assertEquals("SHOW CREATE TABLE \"public\"\"; DROP TABLE users; --\".\"t\"", sql);
    }

    @Test
    void maliciousTableNameIsNeutralizedInShowCreateTable() {
        String sql = RedshiftMetaData.buildShowCreateTableSql("public", "t\" OR \"1\"=\"1");
        assertEquals("SHOW CREATE TABLE \"public\".\"t\"\" OR \"\"1\"\"=\"\"1\"", sql);
        assertTrue(sql.startsWith("SHOW CREATE TABLE \"public\"."));
    }

    @Test
    void benignNamesProduceSameSqlAsBefore() {
        assertEquals("SHOW CREATE TABLE \"public\".\"orders\"",
                RedshiftMetaData.buildShowCreateTableSql("public", "orders"));
    }

    @Test
    void showCreateTableHandlesOptionalSchemaAndRejectsEmptyTable() {
        assertEquals("SHOW CREATE TABLE \"orders\"",
                RedshiftMetaData.buildShowCreateTableSql(null, "orders"));
        assertEquals("SHOW CREATE TABLE \"orders\"",
                RedshiftMetaData.buildShowCreateTableSql("", "orders"));
        assertEquals("SHOW CREATE TABLE \" \".\" \"",
                RedshiftMetaData.buildShowCreateTableSql(" ", " "));
        assertThrows(IllegalArgumentException.class,
                () -> RedshiftMetaData.buildShowCreateTableSql("public", ""));
        assertThrows(IllegalArgumentException.class,
                () -> RedshiftMetaData.buildShowCreateTableSql("public", null));
    }
}
