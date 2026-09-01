package ai.chat2db.plugin.mysql;

import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlViewSqlBuilderTest {

    @Test
    void dropViewUsesMysqlDatabaseQualifiedNameWhenSchemaIsPresent() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();

        assertEquals("DROP VIEW IF EXISTS `analytics`.`monthly_summary`",
                builder.buildDropView("analytics", "reporting", "monthly_summary"));
    }

    @Test
    void dropViewFallsBackToDatabaseQualifiedQuotedNameForMysqlDatabaseOnlyScope() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();

        assertEquals("DROP VIEW IF EXISTS `analytics`.`monthly_summary`",
                builder.buildDropView("analytics", null, "monthly_summary"));
    }
}
