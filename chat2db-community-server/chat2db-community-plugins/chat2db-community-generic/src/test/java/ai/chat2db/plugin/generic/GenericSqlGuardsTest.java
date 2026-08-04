package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.constant.DBConfigConstants;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenericSqlGuardsTest {

    private static final String DUCKDB_TABLE_DDL_TEMPLATE =
            "select sql from duckdb_tables() where database_name = '{database}' and schema_name = '{schema}' and table_name = '{table}'";
    private static final String TDENGINE_TABLE_DDL_TEMPLATE = "SHOW CREATE TABLE {database}.{table}";
    private static final String TDENGINE_CHANGE_DATABASE_TEMPLATE = "USE {database}";

    @Test
    void requireSafeIdentifierAcceptsStrictNames() {
        assertEquals("db1", GenericSqlGuards.requireSafeIdentifier("db1", "database"));
        assertEquals("_sys", GenericSqlGuards.requireSafeIdentifier("_sys", "schema"));
        assertEquals("a$B", GenericSqlGuards.requireSafeIdentifier("a$B", "table"));
        assertEquals("T2$x_y", GenericSqlGuards.requireSafeIdentifier("T2$x_y", "table"));
    }

    @Test
    void requireSafeIdentifierRejectsUnsafeNames() {
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("a b", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("a'b", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("a\"b", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("a`b", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("a;b", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("a.b", "table"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("db; SHUTDOWN", "database"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier("", "database"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.requireSafeIdentifier(null, "database"));
    }

    @Test
    void sanitizeTemplateValueTreatsQuotedPlaceholderAsLiteral() {
        assertEquals("x'' OR ''1''=''1",
                GenericSqlGuards.sanitizeTemplateValue(DUCKDB_TABLE_DDL_TEMPLATE, "{table}", "x' OR '1'='1"));
    }

    @Test
    void sanitizeTemplateValueTreatsBarePlaceholderAsIdentifier() {
        assertEquals("test",
                GenericSqlGuards.sanitizeTemplateValue(TDENGINE_CHANGE_DATABASE_TEMPLATE, "{database}", "test"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.sanitizeTemplateValue(TDENGINE_CHANGE_DATABASE_TEMPLATE, "{database}",
                        "test; SHUTDOWN"));
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.sanitizeTemplateValue(TDENGINE_TABLE_DDL_TEMPLATE, "{table}", "a b"));
    }

    @Test
    void sanitizeTemplateValuePassesThroughBlankAndNullTemplate() {
        assertEquals("a b", GenericSqlGuards.sanitizeTemplateValue(null, "{table}", "a b"));
        assertNull(GenericSqlGuards.sanitizeTemplateValue(DUCKDB_TABLE_DDL_TEMPLATE, "{table}", null));
        assertEquals("", GenericSqlGuards.sanitizeTemplateValue(DUCKDB_TABLE_DDL_TEMPLATE, "{table}", ""));
    }

    @Test
    void duckdbTableDdlNeutralizesMaliciousLiteral() {
        DBConfig config = configWith(DBConfigConstants.SQL_TABLE_DDL, DUCKDB_TABLE_DDL_TEMPLATE);
        String template = config.getSql(DBConfigConstants.SQL_TABLE_DDL);
        String databaseName = GenericSqlGuards.sanitizeTemplateValue(template, "{database}", "main");
        String schemaName = GenericSqlGuards.sanitizeTemplateValue(template, "{schema}", "main");
        String tableName = GenericSqlGuards.sanitizeTemplateValue(template, "{table}", "x' OR '1'='1");
        assertEquals("select sql from duckdb_tables() where database_name = 'main' and schema_name = 'main'"
                        + " and table_name = 'x'' OR ''1''=''1'",
                config.getTableDdl(databaseName, schemaName, tableName));
    }

    @Test
    void tdengineTableDdlRejectsMaliciousIdentifier() {
        DBConfig config = configWith(DBConfigConstants.SQL_TABLE_DDL, TDENGINE_TABLE_DDL_TEMPLATE);
        String template = config.getSql(DBConfigConstants.SQL_TABLE_DDL);
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.sanitizeTemplateValue(template, "{database}", "test`; SHUTDOWN; --"));
        String databaseName = GenericSqlGuards.sanitizeTemplateValue(template, "{database}", "test");
        String tableName = GenericSqlGuards.sanitizeTemplateValue(template, "{table}", "t1");
        assertEquals("SHOW CREATE TABLE test.t1", config.getTableDdl(databaseName, null, tableName));
    }

    @Test
    void tdengineChangeDatabaseRejectsMaliciousIdentifier() {
        DBConfig config = configWith(DBConfigConstants.SQL_CHANGE_DATABASE, TDENGINE_CHANGE_DATABASE_TEMPLATE);
        String template = config.getSql(DBConfigConstants.SQL_CHANGE_DATABASE);
        assertThrows(IllegalArgumentException.class,
                () -> GenericSqlGuards.sanitizeTemplateValue(template, "{database}", "test; DROP DATABASE x"));
        String database = GenericSqlGuards.sanitizeTemplateValue(template, "{database}", "test");
        assertEquals("USE test", config.getChangeDatabase(database, null));
    }

    private static DBConfig configWith(String key, String template) {
        DBConfig config = new DBConfig();
        Map<String, String> sqlMap = new HashMap<>();
        sqlMap.put(key, template);
        config.setSqlMap(sqlMap);
        return config;
    }
}
