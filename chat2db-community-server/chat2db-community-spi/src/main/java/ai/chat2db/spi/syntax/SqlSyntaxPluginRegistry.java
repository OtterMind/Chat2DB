package ai.chat2db.spi.syntax;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link ISqlSyntaxPlugin} implementations through {@link ServiceLoader}
 * and routes them by database type, so configuration-driven plugins can resolve a
 * dialect without compile-time references to concrete syntax plugin classes.
 */
public final class SqlSyntaxPluginRegistry {

    private static final Map<String, ISqlSyntaxPlugin> PLUGINS = load();

    private SqlSyntaxPluginRegistry() {
    }

    private static Map<String, ISqlSyntaxPlugin> load() {
        Map<String, ISqlSyntaxPlugin> plugins = new HashMap<>();
        for (ISqlSyntaxPlugin plugin : ServiceLoader.load(ISqlSyntaxPlugin.class)) {
            String key = normalize(plugin.getDatabaseType());
            if (key != null) {
                plugins.putIfAbsent(key, plugin);
            }
        }
        return plugins;
    }

    /**
     * Finds the syntax plugin registered for the given database type.
     *
     * @param databaseType database type value, matched case-insensitively.
     * @return the registered plugin, or empty when none matches.
     */
    public static Optional<ISqlSyntaxPlugin> find(String databaseType) {
        String key = normalize(databaseType);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PLUGINS.get(key));
    }

    private static String normalize(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
