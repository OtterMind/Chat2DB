package ai.chat2db.spi.config;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.SupportedDatabaseSummary;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the list of databases currently registered in the plugin map, so the
 * supported-database inventory can be served to clients from configuration
 * instead of being hardcoded on the frontend.
 */
public final class SupportedDatabaseRegistry {

    private SupportedDatabaseRegistry() {
    }

    public static List<SupportedDatabaseSummary> listSupportedDatabases() {
        return summarize(Chat2DBContext.PLUGIN_MAP);
    }

    static List<SupportedDatabaseSummary> summarize(Map<String, IPlugin> plugins) {
        List<SupportedDatabaseSummary> result = new ArrayList<>();
        if (plugins == null) {
            return result;
        }
        for (IPlugin plugin : plugins.values()) {
            if (plugin == null) {
                continue;
            }
            DBConfig config = plugin.getDBConfig();
            if (config == null || StringUtils.isBlank(config.getDbType())) {
                continue;
            }
            DriverConfig driver = config.getDefaultDriverConfig();
            result.add(SupportedDatabaseSummary.builder()
                    .dbType(config.getDbType())
                    .name(StringUtils.defaultIfBlank(config.getName(), config.getDbType()))
                    .supportDatabase(config.isSupportDatabase())
                    .supportSchema(config.isSupportSchema())
                    .sqlDialect(config.getSqlDialect())
                    .jdbcDriverClass(driver == null ? null : driver.getJdbcDriverClass())
                    .urlSample(driver == null ? null : driver.getUrl())
                    .icon(config.getIcon())
                    .build());
        }
        result.sort(Comparator.comparing(SupportedDatabaseSummary::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}
