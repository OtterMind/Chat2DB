package ai.chat2db.community.test.spi.util;

import ai.chat2db.community.domain.api.model.sql.SqlParameterType;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.util.SqlParameterParser;
import ai.chat2db.spi.util.SqlParameterParser.BoundSql;
import ai.chat2db.spi.util.SqlParameterParser.NormalizedSql;
import ai.chat2db.spi.util.SqlParameterParser.Placeholder;
import ai.chat2db.spi.util.SqlParameterParser.Style;
import ai.chat2db.spi.util.SqlParameterSyntax;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterParserTest {

    private static final SqlParameterSyntax ANSI = SqlParameterSyntax.ANSI;

    @Test
    void namedParameterIsDetected() {
        assertEquals(List.of("id"), names("SELECT * FROM users WHERE id = :id;", ANSI));
    }

    @Test
    void duplicateNamedParameterIsOneLogicalParameterBoundToEveryOccurrence() {
        String sql = "SELECT *\nFROM users\nWHERE id = :id OR manager_id = :id;";

        assertEquals(List.of("id", "id"), names(sql, ANSI));
        BoundSql bound = SqlParameterParser.compile(sql, Map.of("id", SqlParameterValue.number("123")), ANSI);

        assertEquals("SELECT *\nFROM users\nWHERE id = ? OR manager_id = ?;", bound.sql());
        assertEquals(List.of(SqlParameterValue.number("123"), SqlParameterValue.number("123")), bound.values());
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
    void positionalParametersAreNormalisedToSyntheticNamesLeftToRight() {
        String sql = "SELECT *\nFROM users\nWHERE name = ? AND age > ?;";

        NormalizedSql normalized = SqlParameterParser.normalize(sql, null,
                List.of(SqlParameterValue.string("alice"), SqlParameterValue.number("30")), ANSI);

        assertEquals("SELECT *\nFROM users\nWHERE name = :__p1 AND age > :__p2;", normalized.sql());
        assertTrue(normalized.positional());
        assertEquals(Map.of("__p1", SqlParameterValue.string("alice"), "__p2", SqlParameterValue.number("30")),
                normalized.parameters());
        assertEquals(sql, SqlParameterParser.restorePositional(normalized.sql(), ANSI));
    }

    @Test
    void positionalValuesSurviveARewriteThatDropsEarlierPlaceholders() {
        NormalizedSql normalized = SqlParameterParser.normalize(
                "SELECT ? AS tag, name FROM users WHERE age > ? ORDER BY ?", null,
                List.of(SqlParameterValue.string("x"), SqlParameterValue.number("30"),
                        SqlParameterValue.number("1")), ANSI);
        // A COUNT rewrite drops the projection and ORDER BY; only the WHERE value remains.
        String countSql = "SELECT COUNT(*) FROM users WHERE age > :__p2";

        BoundSql bound = SqlParameterParser.compile(countSql, normalized.parameters(), ANSI);

        assertEquals("SELECT COUNT(*) FROM users WHERE age > ?", bound.sql());
        assertEquals(List.of(SqlParameterValue.number("30")), bound.values());
    }

    @Test
    void syntheticNamesStaySeparateTokens() {
        NormalizedSql normalized = SqlParameterParser.normalize("SELECT a FROM t WHERE x=?AND y IN(?,?)", null,
                List.of(SqlParameterValue.number("1"), SqlParameterValue.number("2"), SqlParameterValue.number("3")),
                ANSI);

        assertEquals(List.of("__p1", "__p2", "__p3"), names(normalized.sql(), ANSI));
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
    void mixedStylesAreRejected() {
        BusinessException exception = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT *\nFROM users\nWHERE id = :id AND status = ?;",
                Map.of("id", SqlParameterValue.number("1")), null, ANSI));

        assertEquals("sqlParameter.mixedStyles", exception.getCode());
    }

    @Test
    void missingNamedValueIsRejectedWithTheParameterName() {
        BusinessException exception = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT * FROM users WHERE id = :id AND status = :status",
                Map.of("id", SqlParameterValue.number("1")), null, ANSI));

        assertEquals("sqlParameter.missing", exception.getCode());
        assertArrayEquals(new Object[]{"status"}, exception.getArgs());
    }

    @Test
    void missingPositionalValueIsRejectedWithTheParameterNumber() {
        BusinessException exception = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT * FROM users WHERE name = ? AND age > ?", null,
                List.of(SqlParameterValue.string("alice")), ANSI));

        assertEquals("sqlParameter.missing", exception.getCode());
        assertArrayEquals(new Object[]{2}, exception.getArgs());
    }

    @Test
    void valuesThatDoNotMatchThePlaceholdersAreRejected() {
        assertInvalid("SELECT :id", Map.of("id", SqlParameterValue.number("1"),
                "other", SqlParameterValue.number("2")), null);
        assertInvalid("SELECT :id", null, List.of(SqlParameterValue.number("1")));
        assertInvalid("SELECT ?", Map.of("id", SqlParameterValue.number("1")), null);
        assertInvalid("SELECT ?", null, List.of(SqlParameterValue.number("1"), SqlParameterValue.number("2")));
        assertInvalid("SELECT :id", Map.of("id", SqlParameterValue.number("1")),
                List.of(SqlParameterValue.number("1")));
    }

    @Test
    void valuesForSqlWithoutPlaceholdersAreRejected() {
        BusinessException exception = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT ':id'", Map.of("id", SqlParameterValue.number("1")), null, ANSI));

        assertEquals("sqlParameter.notFound", exception.getCode());
    }

    @Test
    void noValuesMeansNoParameterisedExecution() {
        assertNull(SqlParameterParser.normalize("SELECT :id", null, null, ANSI));
        assertNull(SqlParameterParser.normalize("SELECT :id", Map.of(), List.of(), ANSI));
    }

    @Test
    void everyValueNeedsAKnownType() {
        BusinessException missingType = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT :id", Map.of("id", new SqlParameterValue(null, "1")), null, ANSI));
        BusinessException nullEntry = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT ?", null, Arrays.asList((SqlParameterValue) null), ANSI));

        assertEquals("sqlParameter.invalidType", missingType.getCode());
        assertArrayEquals(new Object[]{"id"}, missingType.getArgs());
        assertEquals("sqlParameter.invalidType", nullEntry.getCode());
    }

    @Test
    void valuesMustBeValidForTheirType() {
        for (String number : List.of("42", "-1", "+3.50", ".5", "1e10", "6.02E-23")) {
            assertNotNull(SqlParameterParser.normalize("SELECT :n", Map.of("n", SqlParameterValue.number(number)),
                    null, ANSI), number);
        }
        for (String number : Arrays.asList("", " 1", "1,000", "abc", "0x10", "NaN", null)) {
            assertInvalidValue(SqlParameterValue.number(number), "NUMBER");
        }
        assertNotNull(SqlParameterParser.normalize("SELECT :b", Map.of("b", SqlParameterValue.bool("false")),
                null, ANSI));
        for (String bool : Arrays.asList("TRUE", "1", "yes", "", null)) {
            assertInvalidValue(SqlParameterValue.bool(bool), "BOOLEAN");
        }
        assertInvalidValue(new SqlParameterValue(SqlParameterType.NULL, "x"), "NULL");
        assertInvalidValue(SqlParameterValue.string(null), "STRING");
    }

    @Test
    void nullEmptyStringAndMissingStayDistinct() {
        NormalizedSql normalized = SqlParameterParser.normalize("SELECT ?, ?", null,
                List.of(SqlParameterValue.nullValue(), SqlParameterValue.string("")), ANSI);
        BoundSql bound = SqlParameterParser.compile(normalized.sql(), normalized.parameters(), ANSI);

        assertEquals(List.of(SqlParameterValue.nullValue(), SqlParameterValue.string("")), bound.values());
        BusinessException missing = assertThrows(BusinessException.class, () -> SqlParameterParser.normalize(
                "SELECT :a, :b", Map.of("a", SqlParameterValue.string("")), null, ANSI));
        assertEquals("sqlParameter.missing", missing.getCode());
    }

    @Test
    void compileRejectsNamesWithoutValuesAndBarePositionalMarkers() {
        BusinessException missing = assertThrows(BusinessException.class, () -> SqlParameterParser.compile(
                "SELECT :a", Map.of("b", SqlParameterValue.number("1")), ANSI));
        BusinessException positional = assertThrows(BusinessException.class, () -> SqlParameterParser.compile(
                "SELECT :a, ?", Map.of("a", SqlParameterValue.number("1")), ANSI));

        assertEquals("sqlParameter.missing", missing.getCode());
        assertEquals("sqlParameter.mixedStyles", positional.getCode());
    }

    @Test
    void restoringOnlyTouchesSyntheticNames() {
        assertEquals("SELECT ?, :id, ':__p1', :__p, :__p01",
                SqlParameterParser.restorePositional("SELECT :__p1, :id, ':__p1', :__p, :__p01", ANSI));
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
    void bindingNeverCopiesValuesIntoTheSql() {
        String hostile = "1; DROP TABLE users; --' OR '1'='1";
        NormalizedSql normalized = SqlParameterParser.normalize("SELECT name FROM users WHERE id = :id",
                Map.of("id", SqlParameterValue.string(hostile)), null, ANSI);
        BoundSql bound = SqlParameterParser.compile(normalized.sql(), normalized.parameters(), ANSI);

        assertEquals("SELECT name FROM users WHERE id = ?", bound.sql());
        assertFalse(bound.sql().contains(hostile));
        assertEquals(List.of(SqlParameterValue.string(hostile)), bound.values());
        assertFalse(bound.toString().contains(hostile), "values must stay out of logs");
        assertFalse(normalized.toString().contains(hostile), "values must stay out of logs");
        assertFalse(SqlParameterValue.string(hostile).toString().contains(hostile), "values must stay out of logs");
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

    private static void assertInvalid(String sql, Map<String, SqlParameterValue> named,
                                      List<SqlParameterValue> positional) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> SqlParameterParser.normalize(sql, named, positional, ANSI));
        assertEquals("sqlParameter.invalid", exception.getCode(), sql);
    }

    private static void assertInvalidValue(SqlParameterValue value, String type) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> SqlParameterParser.normalize("SELECT :v", Map.of("v", value), null, ANSI),
                () -> type + " " + value.getValue());
        assertEquals("sqlParameter.invalidValue", exception.getCode());
        assertArrayEquals(new Object[]{"v", type}, exception.getArgs());
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
