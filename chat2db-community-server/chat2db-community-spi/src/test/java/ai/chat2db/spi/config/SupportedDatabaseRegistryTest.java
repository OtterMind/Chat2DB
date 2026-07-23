package ai.chat2db.spi.config;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.SupportedDatabaseSummary;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.IPlugin;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedDatabaseRegistryTest {

    private IPlugin plugin(DBConfig config) {
        return new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }
        };
    }

    private DBConfig config(String dbType, String name, String dialect, DriverConfig driver) {
        DBConfig config = new DBConfig();
        config.setDbType(dbType);
        config.setName(name);
        config.setSqlDialect(dialect);
        if (driver != null) {
            config.setDriverConfigList(List.of(driver));
        }
        return config;
    }

    @Test
    void summarizesRegisteredPluginsSortedByNameWithDriverInfo() {
        DriverConfig driver = new DriverConfig();
        driver.setJdbcDriverClass("org.firebirdsql.jdbc.FBDriver");
        driver.setUrl("jdbc:firebirdsql://localhost:3050/db");
        Map<String, IPlugin> plugins = new LinkedHashMap<>();
        plugins.put("ZULU_DB", plugin(config("ZULU_DB", "Zulu", null, null)));
        plugins.put("FIREBIRD", plugin(config("FIREBIRD", "Firebird", "POSTGRESQL", driver)));

        List<SupportedDatabaseSummary> result = SupportedDatabaseRegistry.summarize(plugins);

        assertEquals(2, result.size());
        assertEquals("Firebird", result.get(0).getName());
        assertEquals("POSTGRESQL", result.get(0).getSqlDialect());
        assertEquals("org.firebirdsql.jdbc.FBDriver", result.get(0).getJdbcDriverClass());
        assertEquals("jdbc:firebirdsql://localhost:3050/db", result.get(0).getUrlSample());
        assertEquals("Zulu", result.get(1).getName());
        assertNull(result.get(1).getJdbcDriverClass());
    }

    @Test
    void fallsBackToDbTypeWhenNameIsBlank() {
        Map<String, IPlugin> plugins = Map.of("X", plugin(config("X_DB", " ", null, null)));

        assertEquals("X_DB", SupportedDatabaseRegistry.summarize(plugins).get(0).getName());
    }

    @Test
    void skipsNullPluginsNullConfigsAndBlankDbTypes() {
        Map<String, IPlugin> plugins = new LinkedHashMap<>();
        plugins.put("a", null);
        plugins.put("b", plugin(null));
        plugins.put("c", plugin(config(" ", "NoType", null, null)));

        assertTrue(SupportedDatabaseRegistry.summarize(plugins).isEmpty());
    }

    @Test
    void toleratesNullMap() {
        assertTrue(SupportedDatabaseRegistry.summarize(null).isEmpty());
    }
}
