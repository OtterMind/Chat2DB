package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the configuration-only database registry: every generic.json entry
 * must load with a usable driver declaration, and Firebird is the first
 * database added purely through configuration.
 */
class GenericPluginConfigLoadTest {

    private final List<DBConfig> configs = new GenericPlugin().getDBConfigList();

    @Test
    void everyEntryDeclaresDbTypeAndCompleteDefaultDriver() {
        assertFalse(configs.isEmpty());
        for (DBConfig config : configs) {
            assertNotNull(config.getDbType());
            DriverConfig driver = config.getDefaultDriverConfig();
            assertNotNull(driver, config.getDbType() + " missing default driver");
            assertNotNull(driver.getJdbcDriverClass(), config.getDbType() + " missing driver class");
            assertNotNull(driver.getUrl(), config.getDbType() + " missing url template");
            assertNotNull(driver.getJdbcDriver(), config.getDbType() + " missing driver jar name");
        }
    }

    @Test
    void firebirdIsRegisteredThroughConfigurationOnly() {
        Optional<DBConfig> firebird = configs.stream()
                .filter(config -> "FIREBIRD".equals(config.getDbType())).findFirst();

        assertTrue(firebird.isPresent());
        assertEquals("Firebird", firebird.get().getName());
        assertEquals("org.firebirdsql.jdbc.FBDriver",
                firebird.get().getDefaultDriverConfig().getJdbcDriverClass());
        assertTrue(firebird.get().isSupportDatabase());
        assertFalse(firebird.get().isSupportSchema());
    }
}
