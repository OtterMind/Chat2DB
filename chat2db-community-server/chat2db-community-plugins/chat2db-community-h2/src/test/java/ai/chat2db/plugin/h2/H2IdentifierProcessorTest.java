package ai.chat2db.plugin.h2;

import java.sql.Types;

import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.plugin.h2.builder.H2SqlBuilder;
import ai.chat2db.plugin.h2.identifier.H2IdentifierProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2IdentifierProcessorTest {

    @Test
    void escapeSqlLiteralDoublesSingleQuotes() {
        assertEquals("O''Brien", H2IdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertNull(H2IdentifierProcessor.INSTANCE.escapeString(null));
        assertEquals("plain", H2IdentifierProcessor.INSTANCE.escapeString("plain"));
    }

    @Test
    void identifierQuotingPreservesRawValuesAndRoundTrips() {
        assertEquals("\"A\"\"B\"", H2IdentifierProcessor.INSTANCE.quoteIdentifier("A\"B"));
        assertEquals("\"\"\"ALREADY\"\"\"",
            H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"ALREADY\""));
        assertEquals("A\"B", H2IdentifierProcessor.INSTANCE.removeIdentifierQuote(
            H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("A\"B")));
        assertEquals("\"ALREADY\"", H2IdentifierProcessor.INSTANCE.removeIdentifierQuote(
            H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"ALREADY\"")));
    }

    @Test
    void getMetaDataNameNeutralizesEmbeddedQuotes() {
        H2Meta meta = new H2Meta();
        String result = meta.getMetaDataName("PUBLIC", "A\".\"B");
        assertEquals("\"PUBLIC\".\"A\"\".\"\"B\"", result);
        assertFalse(result.contains("A\".\"B\"."), "injection payload must not break out of the quoted identifier");
    }

    @Test
    void buildCreateSchemaEscapesNameAndComment() {
        Schema schema = new Schema();
        schema.setName("EVIL\" SCHEMA");
        schema.setComment("x'); DROP TABLE USERS; --");
        String sql = new H2SqlBuilder().buildCreateSchema(schema);
        assertEquals("CREATE SCHEMA \"EVIL\"\" SCHEMA\";\n"
            + "COMMENT ON SCHEMA \"EVIL\"\" SCHEMA\" IS 'x''); DROP TABLE USERS; --';", sql);
    }

    @Test
    void dropTableQuotesAndEscapesTableName() {
        String sql = new H2DBManager().dropTable(null, "TEST", "PUBLIC", "T\"; DROP TABLE U; --");
        assertEquals("DROP TABLE \"T\"\"; DROP TABLE U; --\"", sql);
    }

    @Test
    void identifierProcessorKeepsConditionalAndAlwaysQuoteTracksSeparate() {
        assertNull(H2IdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertNull(H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("", H2IdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals("\"\"\"\"", H2IdentifierProcessor.INSTANCE.quoteIdentifier("\""));
        assertEquals("PLAIN", H2IdentifierProcessor.INSTANCE.quoteIdentifier("PLAIN"));
        assertEquals("\"plain\"", H2IdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("\"SELECT\"", H2IdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
        assertEquals("\"plain\"", H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("\"\"", H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
        assertEquals("PLAIN", H2IdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("PLAIN"));
    }

    @Test
    void requireSafeTypeNameAcceptsRealH2TypesAndRejectsInjection() {
        assertEquals("INTEGER", H2SqlGuards.requireSafeTypeName("INTEGER"));
        assertEquals("CHARACTER VARYING", H2SqlGuards.requireSafeTypeName("CHARACTER VARYING"));
        assertEquals("DOUBLE PRECISION", H2SqlGuards.requireSafeTypeName("DOUBLE PRECISION"));
        assertThrows(IllegalArgumentException.class,
            () -> H2SqlGuards.requireSafeTypeName("INT; DROP TABLE USERS; --"));
        assertThrows(IllegalArgumentException.class,
            () -> H2SqlGuards.requireSafeTypeName("INT')"));
        assertThrows(IllegalArgumentException.class,
            () -> H2SqlGuards.requireSafeTypeName("INTEGER) NOT NULL"));
        assertNull(H2SqlGuards.requireSafeTypeName(null));
    }

    @Test
    void renderMetadataTypeUsesJdbcPrecisionSemantics() {
        assertEquals("BIGINT", H2SqlGuards.renderMetadataType("BIGINT", Types.BIGINT, 64, 0));
        assertEquals("CHARACTER VARYING(64)",
            H2SqlGuards.renderMetadataType("CHARACTER VARYING", Types.VARCHAR, 64, 0));
        assertEquals("NUMERIC(12,3)",
            H2SqlGuards.renderMetadataType("NUMERIC", Types.NUMERIC, 12, 3));
        assertEquals("TIMESTAMP(6)",
            H2SqlGuards.renderMetadataType("TIMESTAMP", Types.TIMESTAMP, 26, 6));
    }

    @Test
    void escapeColumnDefaultKeepsWellFormedLiteralsAndExpressions() {
        assertEquals("'O''Brien'", H2SqlGuards.escapeColumnDefault("'O''Brien'"));
        assertEquals("CURRENT_TIMESTAMP", H2SqlGuards.escapeColumnDefault("CURRENT_TIMESTAMP"));
        assertEquals("42", H2SqlGuards.escapeColumnDefault("42"));
        assertEquals("-1", H2SqlGuards.escapeColumnDefault("-1"));
        assertEquals("NEXT VALUE FOR SEQ1", H2SqlGuards.escapeColumnDefault("NEXT VALUE FOR SEQ1"));
        assertEquals("NEXT VALUE FOR \"PUBLIC\".\"SEQ1\"",
            H2SqlGuards.escapeColumnDefault("NEXT VALUE FOR \"PUBLIC\".\"SEQ1\""));
        assertEquals("DATE '2026-07-29'", H2SqlGuards.escapeColumnDefault("DATE '2026-07-29'"));
        assertEquals("RANDOM_UUID()", H2SqlGuards.escapeColumnDefault("RANDOM_UUID()"));
        assertEquals("", H2SqlGuards.escapeColumnDefault(null));
    }

    @Test
    void escapeColumnDefaultRejectsStructuralBreakout() {
        assertThrows(IllegalArgumentException.class,
            () -> H2SqlGuards.escapeColumnDefault("'x'); DROP TABLE USERS; --'"));
        assertThrows(IllegalArgumentException.class,
            () -> H2SqlGuards.escapeColumnDefault("0; DROP TABLE USERS; --"));
        assertThrows(IllegalArgumentException.class,
            () -> H2SqlGuards.escapeColumnDefault("0, INJECTED INTEGER"));
        assertEquals("'||'", H2SqlGuards.escapeColumnDefault("'||'"));
    }
}
