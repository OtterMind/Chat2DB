package ai.chat2db.spi.util;

import com.alibaba.druid.DbType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlUtilsTest {

    @Test
    void countTrimsGeneratedSqlSemicolonWithoutTruncatingCountSql() {
        assertEquals("SELECT COUNT(*) FROM users", SqlUtils.count("SELECT * FROM users;", "mysql"));
    }

    @Test
    void trimTrailingSemicolonUsesInputSqlLength() {
        assertEquals("SELECT COUNT(*) FROM users",
                SqlUtils.trimTrailingSemicolon("SELECT COUNT(*) FROM users;"));
    }

    @Test
    void updateNowRewritesColumnDefaultInDdl() throws Exception {
        assertEquals("CREATE TABLE t (a DATETIME default CURRENT_TIMESTAMP);",
                updateNow("CREATE TABLE t (a DATETIME DEFAULT now());"));
        assertEquals("CREATE TABLE t (a DATETIME default CURRENT_TIMESTAMP);",
                updateNow("CREATE TABLE t (a DATETIME default now ());"));
    }

    @Test
    void updateNowLeavesQuotedStringLiteralsUntouched() throws Exception {
        String singleQuoted = "INSERT INTO t (a) VALUES ('default now()');";
        assertEquals(singleQuoted, updateNow(singleQuoted));

        String escapedQuote = "INSERT INTO t (a) VALUES ('it''s default now()');";
        assertEquals(escapedQuote, updateNow(escapedQuote));

        String doubleQuoted = "INSERT INTO t (a) VALUES (\"DEFAULT now ()\");";
        assertEquals(doubleQuoted, updateNow(doubleQuoted));
    }

    @Test
    void updateNowLeavesCommentsAndLongerIdentifiersUntouched() throws Exception {
        String sql = """
                -- default now()
                # DEFAULT now ()
                /* default now() */
                SELECT nodefault now();
                """;

        assertEquals(sql, updateNow(sql));
    }

    @Test
    void updateNowAcceptsSqlWhitespaceAroundFunctionTokens() throws Exception {
        assertEquals("x default CURRENT_TIMESTAMP",
                updateNow("x default\t now ( \n )"));
    }

    @Test
    void updateNowEmitsConsistentCasingAcrossVariants() throws Exception {
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x DEFAULT now()"));
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x default now()"));
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x DEFAULT now ()"));
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x default now ()"));
    }

    private static String updateNow(String sql) throws Exception {
        Method method = SqlUtils.class.getDeclaredMethod("updateNow", String.class, DbType.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql, DbType.mysql);
    }
}
