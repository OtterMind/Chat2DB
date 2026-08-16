package ai.chat2db.spi.syntax;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSyntaxPluginRegistryTest {

    @Test
    void findsServiceLoaderRegisteredPluginByDatabaseType() {
        Optional<ISqlSyntaxPlugin> plugin = SqlSyntaxPluginRegistry.find("REGISTRY_PROBE");

        assertTrue(plugin.isPresent());
        assertTrue(plugin.get() instanceof RegistryProbeSyntaxPlugin);
    }

    @Test
    void matchesDatabaseTypeCaseInsensitivelyAndTrimmed() {
        assertTrue(SqlSyntaxPluginRegistry.find("registry_probe").isPresent());
        assertTrue(SqlSyntaxPluginRegistry.find("  Registry_Probe  ").isPresent());
    }

    @Test
    void returnsEmptyForUnknownBlankOrNullType() {
        assertFalse(SqlSyntaxPluginRegistry.find("NO_SUCH_DIALECT").isPresent());
        assertFalse(SqlSyntaxPluginRegistry.find("").isPresent());
        assertFalse(SqlSyntaxPluginRegistry.find(null).isPresent());
    }
}
