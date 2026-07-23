package ai.chat2db.spi.config;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.constant.DBConfigConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the SQL template rendering that configuration-only databases rely on
 * for DDL retrieval and database switching.
 */
class DBConfigSqlTemplateTest {

    private DBConfig config(String key, String template) {
        DBConfig config = new DBConfig();
        config.setSqlMap(Map.of(key, template));
        return config;
    }

    @Test
    void rendersTableDdlTemplateWithAllPlaceholders() {
        DBConfig config = config(DBConfigConstants.SQL_TABLE_DDL,
                "SHOW CREATE TABLE {database}.{schema}.{table}");

        assertEquals("SHOW CREATE TABLE d1.s1.t1", config.getTableDdl("d1", "s1", "t1"));
    }

    @Test
    void leavesPlaceholdersUntouchedForBlankArguments() {
        DBConfig config = config(DBConfigConstants.SQL_TABLE_DDL, "DESCRIBE {database}.{table}");

        assertEquals("DESCRIBE {database}.t1", config.getTableDdl(null, null, "t1"));
    }

    @Test
    void rendersChangeDatabaseTemplate() {
        DBConfig config = config(DBConfigConstants.SQL_CHANGE_DATABASE, "USE {database}");

        assertEquals("USE d1", config.getChangeDatabase("d1", null));
    }

    @Test
    void returnsNullWhenTemplateOrSqlMapMissing() {
        assertNull(new DBConfig().getTableDdl("d", "s", "t"));
        assertNull(new DBConfig().getChangeDatabase("d", null));
        assertNull(config("other", "x").getTableDdl("d", "s", "t"));
    }
}
