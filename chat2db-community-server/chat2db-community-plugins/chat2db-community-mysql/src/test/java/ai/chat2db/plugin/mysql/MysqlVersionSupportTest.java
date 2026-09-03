package ai.chat2db.plugin.mysql;

import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlVersionSupportTest {

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
    }

    @Test
    void shouldGateInvisibleColumnsAtMysql8023() {
        assertFalse(MysqlVersionSupport.supportsInvisibleColumns("5.7.44-log"));
        assertFalse(MysqlVersionSupport.supportsInvisibleColumns("8.0.22"));
        assertTrue(MysqlVersionSupport.supportsInvisibleColumns("8.0.23"));
        assertTrue(MysqlVersionSupport.supportsInvisibleColumns("8.4.0-commercial"));
        assertFalse(MysqlVersionSupport.supportsInvisibleColumns("10.6.0-MariaDB"));
        assertFalse(MysqlVersionSupport.supportsInvisibleColumns(null));
    }

    @Test
    void shouldGateInvisibleIndexesAtMysqlEight() {
        assertFalse(MysqlVersionSupport.supportsInvisibleIndexes("5.7.44-log"));
        assertTrue(MysqlVersionSupport.supportsInvisibleIndexes("8.0.11"));
        assertTrue(MysqlVersionSupport.supportsInvisibleIndexes("8.4.0-commercial"));
        assertFalse(MysqlVersionSupport.supportsInvisibleIndexes("10.6.0-MariaDB"));
        assertFalse(MysqlVersionSupport.supportsInvisibleIndexes(null));
    }

    @Test
    void shouldGatePersistedVariablesAtMysqlEight() {
        assertFalse(MysqlVersionSupport.supportsPersistedVariables("5.7.44-log"));
        assertTrue(MysqlVersionSupport.supportsPersistedVariables("8.0.11"));
        assertFalse(MysqlVersionSupport.supportsPersistedVariables("10.6.0-MariaDB"));
        assertFalse(MysqlVersionSupport.supportsPersistedVariables(null));
    }

    @Test
    void shouldDisallowInvisibleIndexesWhenVersionIsUnknown() {
        assertTrue(MysqlVersionSupport.currentVersionDisallowsInvisibleIndexes());
    }

    @Test
    void shouldDisallowInvisibleColumnsWhenVersionIsUnknown() {
        assertTrue(MysqlVersionSupport.currentVersionDisallowsInvisibleColumns());
    }
}
