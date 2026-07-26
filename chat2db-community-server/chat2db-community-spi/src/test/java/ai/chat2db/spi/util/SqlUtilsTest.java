package ai.chat2db.spi.util;

import org.junit.jupiter.api.Test;

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
}
