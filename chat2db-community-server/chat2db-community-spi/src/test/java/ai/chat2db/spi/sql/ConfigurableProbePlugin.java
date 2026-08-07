package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.IPlugin;

import java.util.List;

/**
 * Stands in for the generic adapter on the test classpath: it serves a list of
 * configurations rather than a single one, which is what makes
 * {@link Chat2DBContext} treat it as the template for user-defined types.
 */
public class ConfigurableProbePlugin implements IPlugin {

    /** The type this probe contributes at startup, so tests can treat it as built-in. */
    static final String PROBE_DB_TYPE = "CONFIGURABLE_PROBE";

    private DBConfig dbConfig;

    @Override
    public DBConfig getDBConfig() {
        return dbConfig;
    }

    @Override
    public List<DBConfig> getDBConfigList() {
        DBConfig config = new DBConfig();
        config.setDbType(PROBE_DB_TYPE);
        config.setName("Configurable Probe");
        return List.of(config);
    }

    @Override
    public IPlugin getPlugin(DBConfig config) {
        ConfigurableProbePlugin plugin = new ConfigurableProbePlugin();
        plugin.dbConfig = config;
        return plugin;
    }
}
