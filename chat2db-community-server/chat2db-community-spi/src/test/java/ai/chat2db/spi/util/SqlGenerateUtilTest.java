package ai.chat2db.spi.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link SqlGenerateUtil#generateSelectCountSql}.
 * Covers the Druid fallback path (JSQLParser failure) to verify that the
 * rendered count SQL uses the requested database dialect rather than
 * a hardcoded {@code DbType.sqlserver}.
 */
class SqlGenerateUtilTest {

    /**
     * When JSQLParser cannot parse the SQL, the Druid fallback should
     * render the count SQL using the parsed {@code dbType} (e.g. mysql),
     * not {@code DbType.sqlserver}.
     */
    @Test
    void druidFallbackUsesRequestedDbTypeForMysql() {
        String result = SqlGenerateUtil.generateSelectCountSqlWithDruid(
                "SELECT * FROM `order` LIMIT 5", "mysql");

        assertTrue(result.contains("COUNT"), () -> "Expected COUNT in result: " + result);
        assertTrue(result.contains("`order`"), () -> "Expected MySQL identifier quoting: " + result);
        assertFalse(result.contains("[order]"), () -> "Must not use SQL Server identifier quoting: " + result);
    }

    /**
     * The normal (non-fallback) path should be unchanged: a simple SELECT
     * that JSQLParser can parse should produce a count-wrapped SQL.
     */
    @Test
    void normalPathWrapsSimpleSelectInCount() {
        String result = SqlGenerateUtil.generateSelectCountSql("SELECT * FROM users", "mysql");
        assertTrue(result.contains("COUNT"), () -> "Expected COUNT in: " + result);
    }

    @Test
    void normalPathWrapsGroupedSelectInSubqueryCount() {
        String result = SqlGenerateUtil.generateSelectCountSql(
                "SELECT status, COUNT(*) FROM orders GROUP BY status", "mysql");

        assertEquals("SELECT COUNT(*) FROM (SELECT status, COUNT(*) FROM orders GROUP BY status) chat2db_count_temp_table",
                result);
    }

    @Test
    void druidFallbackWrapsGroupedSelectInSubqueryCount() {
        String result = SqlGenerateUtil.generateSelectCountSqlWithDruid(
                "SELECT status, COUNT(*) FROM orders GROUP BY status", "mysql");

        assertEquals("SELECT COUNT(*) FROM (SELECT status, COUNT(*) FROM orders GROUP BY status) chat2db_count_temp_table",
                result.replaceAll("\\s+", " ").trim());
    }

    @Test
    void normalPathRemovesPagingBeforeCountingGroupedSelect() {
        String result = SqlGenerateUtil.generateSelectCountSql(
                "SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status LIMIT 10 OFFSET 5", "mysql");

        assertTrue(result.contains("GROUP BY status"), () -> "Expected GROUP BY in: " + result);
        assertFalse(result.contains("ORDER BY"), () -> "Must not page grouped count: " + result);
        assertFalse(result.contains("LIMIT"), () -> "Must not page grouped count: " + result);
        assertFalse(result.contains("OFFSET"), () -> "Must not page grouped count: " + result);
    }

    @Test
    void druidFallbackRemovesPagingBeforeCountingGroupedSelect() {
        String result = SqlGenerateUtil.generateSelectCountSqlWithDruid(
                "SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status LIMIT 10 OFFSET 5", "mysql");

        assertTrue(result.contains("GROUP BY status"), () -> "Expected GROUP BY in: " + result);
        assertFalse(result.contains("ORDER BY"), () -> "Must not page grouped count: " + result);
        assertFalse(result.contains("LIMIT"), () -> "Must not page grouped count: " + result);
        assertFalse(result.contains("OFFSET"), () -> "Must not page grouped count: " + result);
    }

    /**
     * An unsupported database type should throw IllegalArgumentException
     * when the fallback path is triggered.
     */
    @Test
    void unsupportedDbTypeThrowsIllegalArgumentInFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGenerateUtil.generateSelectCountSqlWithDruid(
                        "SELECT * FROM users", "unsupported_db"));
    }

    /**
     * A non-SELECT statement should throw in both paths.
     */
    @Test
    void nonSelectStatementThrowsInFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGenerateUtil.generateSelectCountSql("INSERT INTO users VALUES (1)", "mysql"));
    }
}
