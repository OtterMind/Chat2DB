package ai.chat2db.community.test.spi.util;

import ai.chat2db.spi.util.SqlParameterParser;
import ai.chat2db.spi.util.SqlParameterParser.Placeholder;
import ai.chat2db.spi.util.SqlParameterParser.Style;
import ai.chat2db.spi.util.SqlParameterSyntax;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterParserTest {

    private static final SqlParameterSyntax ANSI = SqlParameterSyntax.ANSI;

    @Test
    void namedParameterIsDetected() {
        assertEquals(List.of("id"), names("SELECT * FROM users WHERE id = :id;", ANSI));
    }

    @Test
    void multipleNamedParametersKeepSourceOrder() {
        assertEquals(List.of("id", "status"),
                names("SELECT *\nFROM users\nWHERE id = :id AND status = :status;", ANSI));
    }

    @Test
    void namedParametersAcceptUnderscoresAndDigits() {
        assertEquals(List.of("user_id", "created_at", "email2"),
                names("SELECT 1 WHERE a = :user_id AND b > :created_at AND c = :email2", ANSI));
    }

    @Test
    void positionalParameterIsDetected() {
        List<Placeholder> placeholders = SqlParameterParser.findPlaceholders(
                "SELECT * FROM users WHERE id = ?;", ANSI);

        assertEquals(1, placeholders.size());
        assertEquals(Style.POSITIONAL, placeholders.get(0).style());
    }

    @Test
    void markersInsideStringLiteralsAreIgnored() {
        assertNoPlaceholders("SELECT ':id', '?';", ANSI);
        assertNoPlaceholders("SELECT 'it''s :id ?'", ANSI);
        assertNoPlaceholders("SELECT \"col:id?\" FROM t", ANSI);
        assertNoPlaceholders("SELECT `col:id?` FROM t", ANSI);
    }

    @Test
    void markersInsideLineCommentsAreIgnored() {
        assertNoPlaceholders("-- :id\nSELECT * FROM users;", ANSI);
        assertNoPlaceholders("SELECT *\nFROM users\n-- WHERE id = :id\nWHERE active = true;", ANSI);
    }

    @Test
    void markersInsideBlockCommentsAreIgnored() {
        assertNoPlaceholders("/*\n :id\n*/\nSELECT * FROM users;", ANSI);
        assertNoPlaceholders("SELECT *\nFROM users\n/*\n WHERE id = :id\n*/\nWHERE active = true;", ANSI);
    }

    @Test
    void placeholderAfterCommentIsStillDetected() {
        assertEquals(List.of("id"), names("/* :skip */ SELECT * FROM t -- ?\nWHERE id = :id", ANSI));
    }

    @Test
    void backslashEscapesFollowTheDialect() {
        String sql = "SELECT 'a\\' , :id";

        // MySQL: \' is an escaped quote, so the string continues to the end of the text.
        assertNoPlaceholders(sql, SqlParameterSyntax.MYSQL);
        // ANSI: the string ends at the second quote and :id is a parameter.
        assertEquals(List.of("id"), names(sql, ANSI));
        assertNoPlaceholders("SELECT 'it\\'s :id'", SqlParameterSyntax.MYSQL);
    }

    @Test
    void hashCommentsOnlyApplyToDialectsThatHaveThem() {
        assertNoPlaceholders("SELECT 1 # :id", SqlParameterSyntax.MYSQL);
        assertEquals(List.of("id"), names("SELECT 1 # :id", SqlParameterSyntax.POSTGRESQL));
    }

    @Test
    void postgresqlCastsDollarQuotesAndNestedCommentsAreNotParameters() {
        SqlParameterSyntax postgres = SqlParameterSyntax.POSTGRESQL;

        assertEquals(List.of("id"), names("SELECT :id::int, created::date FROM t", postgres));
        assertNoPlaceholders("SELECT $$ :id ? $$, $tag$ :x $tag$", postgres);
        assertNoPlaceholders("SELECT 1 /* outer /* :inner */ :still_comment */", postgres);
        assertNoPlaceholders("SELECT E'\\' :id'", postgres);
        assertNoPlaceholders("SELECT data ?? 'key' FROM t", postgres);
    }

    @Test
    void sqlServerBracketIdentifiersAreIgnored() {
        assertNoPlaceholders("SELECT [weird:id?] FROM t", SqlParameterSyntax.SQLSERVER);
    }

    @Test
    void oracleAlternativeQuotingIsIgnored() {
        assertNoPlaceholders("SELECT q'[it's :id ?]' FROM dual", SqlParameterSyntax.ORACLE);
        assertEquals(List.of("id"), names("SELECT q'{:x}' FROM dual WHERE id = :id", SqlParameterSyntax.ORACLE));
    }

    @Test
    void colonsThatAreNotParametersAreIgnored() {
        assertNoPlaceholders("SET @a := 1", SqlParameterSyntax.MYSQL);
        assertNoPlaceholders("SELECT payload:field FROM t", ANSI);
        assertNoPlaceholders("SELECT arr[1:2] FROM t", ANSI);
        assertNoPlaceholders("SELECT '12:30:00'", ANSI);
    }

    @Test
    void ddlStatementsKeepRunningAsPlainSql() {
        assertNoPlaceholders("CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW BEGIN :NEW.id := 1; END;",
                SqlParameterSyntax.ORACLE);
        assertNoPlaceholders("-- comment\n  alter table t add c int default ?", ANSI);
    }

    @Test
    void postgresqlQuestionMarkOperatorsAreNeverPlaceholders() {
        SqlParameterSyntax postgres = SqlParameterSyntax.POSTGRESQL;

        assertNoPlaceholders("SELECT data ?| array['a', 'b'], data ?& array['c'] FROM t", postgres);
        assertEquals(1, SqlParameterParser.findPlaceholders("SELECT * FROM t WHERE data ?| :keys AND id = ?",
                postgres).stream().filter(p -> p.style() == Style.POSITIONAL).count());
        // Elsewhere ?| is a placeholder followed by the | operator.
        assertEquals(1, SqlParameterParser.findPlaceholders("SELECT ?|| 'x'", ANSI).size());
    }

    @Test
    void unsupportedDatabasesHaveNoSyntax() {
        assertNull(SqlParameterSyntax.forDatabaseType("MONGODB"));
        assertNull(SqlParameterSyntax.forDatabaseType("redis"));
        assertEquals(SqlParameterSyntax.MYSQL, SqlParameterSyntax.forDatabaseType("mariadb"));
        assertEquals(SqlParameterSyntax.POSTGRESQL, SqlParameterSyntax.forDatabaseType("POSTGRESQL"));
        assertEquals(SqlParameterSyntax.ANSI, SqlParameterSyntax.forDatabaseType("H2"));
        assertTrue(SqlParameterParser.hasPlaceholders("SELECT ?", SqlParameterSyntax.forDatabaseType(null)));
    }

    @Test
    void maskingReplacesPlaceholdersWithSameLengthLiterals() {
        String sql = "SELECT * FROM t WHERE id = :id AND name = ? AND note = ':skip' -- :also\n";

        String masked = SqlParameterParser.maskPlaceholdersAsLiterals(sql, ANSI);

        assertEquals("SELECT * FROM t WHERE id = 000 AND name = 0 AND note = ':skip' -- :also\n", masked);
        assertEquals(sql.length(), masked.length());
    }

    @Test
    void maskingLeavesSqlWithoutPlaceholdersUnchanged() {
        String sql = "SELECT a::text, b FROM t WHERE c = 'x?'";

        assertEquals(sql, SqlParameterParser.maskPlaceholdersAsLiterals(sql, SqlParameterSyntax.POSTGRESQL));
    }

    private static void assertNoPlaceholders(String sql, SqlParameterSyntax syntax) {
        assertEquals(List.of(), SqlParameterParser.findPlaceholders(sql, syntax), sql);
    }

    private static List<String> names(String sql, SqlParameterSyntax syntax) {
        List<String> names = new ArrayList<>();
        for (Placeholder placeholder : SqlParameterParser.findPlaceholders(sql, syntax)) {
            assertEquals(Style.NAMED, placeholder.style(), sql);
            names.add(placeholder.name());
        }
        return names;
    }
}
