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
    void identifierQuotesConfigDrivesTheMetadataQuotingBehavior() {
        DBConfig tdengine = configs.stream()
                .filter(config -> "TDENGINE".equals(config.getDbType())).findFirst().orElseThrow();
        GenericMetaData quoted = new GenericMetaData(tdengine);
        assertEquals("`my db`.`order table`", quoted.getMetaDataName("my db", "order table"),
                "TDENGINE declares backtick quoting in generic.json");
        assertEquals("db.orders", quoted.getMetaDataName("db", "orders"),
                "plain identifiers stay unquoted");

        DBConfig firebird = configs.stream()
                .filter(config -> "FIREBIRD".equals(config.getDbType())).findFirst().orElseThrow();
        assertEquals("\"order table\"", new GenericMetaData(firebird)
                        .getSQLIdentifierProcessor().quoteIdentifier("order table"),
                "dialects without identifierQuotes keep the ANSI default");
    }

    @Test
    void tableQualificationConfigDrivesGeneratedTableReferences() {
        DBConfig questdb = configs.stream()
                .filter(config -> "QUESTDB".equals(config.getDbType())).findFirst().orElseThrow();
        GenericMetaData questdbMeta = new GenericMetaData(questdb);
        assertEquals("telemetry", questdbMeta.getQualifiedTableName("qdb", null, "telemetry"),
                "QuestDB has a flat namespace: the database prefix must never reach the SQL");
        assertEquals("\"sys.telemetry_wal\"", questdbMeta.getQualifiedTableName("qdb", null, "sys.telemetry_wal"),
                "dotted system table names still get quoted");

        DBConfig cratedb = configs.stream()
                .filter(config -> "CRATEDB".equals(config.getDbType())).findFirst().orElseThrow();
        assertEquals("crate.c2d_flow_crate",
                new GenericMetaData(cratedb).getQualifiedTableName("doc", "crate", "c2d_flow_crate"),
                "CrateDB rejects its JDBC catalog in SQL: qualify with the schema only");

        DBConfig firebird = configs.stream()
                .filter(config -> "FIREBIRD".equals(config.getDbType())).findFirst().orElseThrow();
        assertEquals("db.orders", new GenericMetaData(firebird).getQualifiedTableName("db", null, "orders"),
                "dialects without tableQualification keep full qualification");

        DBConfig iotdb = configs.stream()
                .filter(config -> "IOTDB".equals(config.getDbType())).findFirst().orElseThrow();
        assertEquals("root.factory.line1",
                new GenericMetaData(iotdb).getQualifiedTableName(null, null, "root.factory.line1"),
                "IoTDB path references must stay raw: dots are structural, quoting breaks the SQL");
    }

    @Test
    void firebirdIsRegisteredThroughConfigurationOnly() {
        Optional<DBConfig> firebird = configs.stream()
                .filter(config -> "FIREBIRD".equals(config.getDbType())).findFirst();

        assertTrue(firebird.isPresent());
        assertEquals("Firebird", firebird.get().getName());
        assertEquals("org.firebirdsql.jdbc.FBDriver",
                firebird.get().getDefaultDriverConfig().getJdbcDriverClass());
        // Firebird has neither catalogs nor schemas: a database level in the
        // tree would always be empty (Jaybird getCatalogs() returns no rows).
        assertFalse(firebird.get().isSupportDatabase());
        assertFalse(firebird.get().isSupportSchema());
    }
}
