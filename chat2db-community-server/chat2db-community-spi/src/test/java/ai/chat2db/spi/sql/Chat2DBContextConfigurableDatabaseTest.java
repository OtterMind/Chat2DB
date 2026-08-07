package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.config.SupportedDatabaseRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Chat2DBContextConfigurableDatabaseTest {

    private static final String CUSTOM_TYPE = "CUSTOM_TEST_DB";

    @AfterEach
    void cleanUp() {
        Chat2DBContext.unregisterConfigurableDatabase(CUSTOM_TYPE);
    }

    private static DBConfig config(String dbType) {
        DBConfig config = new DBConfig();
        config.setDbType(dbType);
        config.setName("Custom Test DB");
        return config;
    }

    @Test
    void registeredTypeBecomesResolvable() {
        Chat2DBContext.registerConfigurableDatabase(config(CUSTOM_TYPE));

        DBConfig resolved = Chat2DBContext.getDBConfig(CUSTOM_TYPE);

        assertNotNull(resolved);
        assertEquals("Custom Test DB", resolved.getName());
    }

    @Test
    void registeredTypeAppearsInSupportedInventory() {
        Chat2DBContext.registerConfigurableDatabase(config(CUSTOM_TYPE));

        assertTrue(SupportedDatabaseRegistry.listSupportedDatabases().stream()
                .anyMatch(summary -> CUSTOM_TYPE.equals(summary.getDbType())));
    }

    @Test
    void reRegisteringReplacesThePreviousDefinition() {
        Chat2DBContext.registerConfigurableDatabase(config(CUSTOM_TYPE));
        DBConfig renamed = config(CUSTOM_TYPE);
        renamed.setName("Renamed");

        Chat2DBContext.registerConfigurableDatabase(renamed);

        assertEquals("Renamed", Chat2DBContext.getDBConfig(CUSTOM_TYPE).getName());
    }

    @Test
    void unregisteringRemovesTheType() {
        Chat2DBContext.registerConfigurableDatabase(config(CUSTOM_TYPE));

        assertTrue(Chat2DBContext.unregisterConfigurableDatabase(CUSTOM_TYPE));
        assertThrows(IllegalArgumentException.class, () -> Chat2DBContext.getDBConfig(CUSTOM_TYPE));
    }

    @Test
    void unregisteringAnUnknownTypeReportsNoRemoval() {
        assertFalse(Chat2DBContext.unregisterConfigurableDatabase("NEVER_REGISTERED"));
    }

    @Test
    void builtInTypesCannotBeShadowed() {
        assertTrue(Chat2DBContext.isBuiltInDatabaseType(ConfigurableProbePlugin.PROBE_DB_TYPE));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Chat2DBContext.registerConfigurableDatabase(config(ConfigurableProbePlugin.PROBE_DB_TYPE)));

        assertTrue(exception.getMessage().contains("already provided by a plugin"));
    }

    @Test
    void builtInTypesCannotBeUnregistered() {
        assertFalse(Chat2DBContext.unregisterConfigurableDatabase(ConfigurableProbePlugin.PROBE_DB_TYPE));

        assertNotNull(Chat2DBContext.getDBConfig(ConfigurableProbePlugin.PROBE_DB_TYPE));
    }

    @Test
    void blankTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Chat2DBContext.registerConfigurableDatabase(config(" ")));
    }
}
