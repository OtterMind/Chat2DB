package ai.chat2db.spi.sql;

import ai.chat2db.spi.IAccountManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.IRoutineManager;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class Chat2DBContext {
    private static final ThreadLocal<ConnectInfo> CONNECT_INFO_THREAD_LOCAL = new ThreadLocal<>();


    public static Map<String, IPlugin> PLUGIN_MAP = new ConcurrentHashMap<>();

    /**
     * Database types that came from a plugin at startup. A user-defined type may
     * not shadow one of these, and deleting a user-defined type may not evict one.
     */
    private static final Set<String> BUILT_IN_DB_TYPES = ConcurrentHashMap.newKeySet();

    /**
     * The plugin that serves a list of configurations rather than a single one -
     * the generic adapter. It is the template used to back user-defined database
     * types, so that a type added at runtime reaches the same metadata, manager
     * and syntax handling as one declared in the adapter's own configuration.
     */
    private static volatile IPlugin configurableTemplate;

    static {
        ServiceLoader<IPlugin> s = ServiceLoader.load(IPlugin.class);
        Iterator<IPlugin> iterator = s.iterator();
        while (iterator.hasNext()) {
            IPlugin plugin = iterator.next();
            DBConfig dbConfig = plugin.getDBConfig();
            if (dbConfig != null) {
                PLUGIN_MAP.put(dbConfig.getDbType(), plugin);
            } else {
                List<DBConfig> dbConfigList = plugin.getDBConfigList();
                if (CollectionUtils.isNotEmpty(dbConfigList)) {
                    configurableTemplate = plugin;
                    for (DBConfig config : dbConfigList) {
                        PLUGIN_MAP.put(config.getDbType(), plugin.getPlugin(config));
                    }
                }
            }
        }
        BUILT_IN_DB_TYPES.addAll(PLUGIN_MAP.keySet());
    }

    /**
     * Whether the type was registered by a plugin at startup.
     */
    public static boolean isBuiltInDatabaseType(String dbType) {
        return dbType != null && BUILT_IN_DB_TYPES.contains(dbType);
    }

    /**
     * Registers a user-defined database type so it becomes connectable and shows
     * up in the supported-database inventory without a restart.
     *
     * @throws IllegalStateException    if no configurable adapter is available to back it.
     * @throws IllegalArgumentException if the type is blank or shadows a built-in one.
     */
    public static void registerConfigurableDatabase(DBConfig config) {
        if (config == null || StringUtils.isBlank(config.getDbType())) {
            throw new IllegalArgumentException("Database type must not be blank.");
        }
        if (isBuiltInDatabaseType(config.getDbType())) {
            throw new IllegalArgumentException(
                    "Database type is already provided by a plugin: " + config.getDbType());
        }
        IPlugin template = configurableTemplate;
        if (template == null) {
            throw new IllegalStateException(
                    "No configurable database adapter is registered; cannot add " + config.getDbType());
        }
        PLUGIN_MAP.put(config.getDbType(), template.getPlugin(config));
    }

    /**
     * Removes a previously registered user-defined type. Built-in types are left
     * untouched, so a stale delete cannot uninstall a shipped database.
     *
     * @return true if a user-defined type was removed.
     */
    public static boolean unregisterConfigurableDatabase(String dbType) {
        if (StringUtils.isBlank(dbType) || isBuiltInDatabaseType(dbType)) {
            return false;
        }
        return PLUGIN_MAP.remove(dbType) != null;
    }

    private static IPlugin getPlugin(String dbType) {
        if (StringUtils.isBlank(dbType)) {
            throw new IllegalArgumentException("Database type must not be blank. Registered types: " + PLUGIN_MAP.keySet());
        }
        IPlugin plugin = PLUGIN_MAP.get(dbType);
        if (plugin == null) {
            throw new IllegalArgumentException("Unsupported database type: " + dbType + ". Registered types: " + PLUGIN_MAP.keySet());
        }
        return plugin;
    }

    public static DriverConfig getDefaultDriverConfig(String dbType) {
        return getPlugin(dbType).getDBConfig().getDefaultDriverConfig();
    }

    public static ISqlBuilder getSqlBuilder() {
        return getPlugin(getConnectInfo().getDbType()).getDbMetaData().getSqlBuilder();
    }


    public static ConnectInfo getConnectInfo() {
        return CONNECT_INFO_THREAD_LOCAL.get();
    }

    public static IDbMetaData getDbMetaData() {
        return getPlugin(getConnectInfo().getDbType()).getDbMetaData();
    }

    public static IDbMetaData getDbMetaData(String dbType) {
        if (StringUtils.isBlank(dbType)) {
            return getDbMetaData();
        }
        return getPlugin(dbType).getDbMetaData();
    }

    public static DBConfig getDBConfig(String dbType) {
        return getPlugin(dbType).getDBConfig();
    }

    public static DBConfig getDBConfig() {
        ConnectInfo connectInfo = getConnectInfo();
        if (connectInfo == null) {
            return null;
        }
        return getPlugin(connectInfo.getDbType()).getDBConfig();
    }

    public static IDbManager getDbManager() {
        return getPlugin(getConnectInfo().getDbType()).getDbManager();
    }

    public static IDbManager getDbManager(String dbType) {
        return getPlugin(dbType).getDbManager();
    }

    public static IAccountManager getAccountManager() {
        ConnectInfo connectInfo = getConnectInfo();
        if (connectInfo == null || StringUtils.isBlank(connectInfo.getDbType())) {
            return null;
        }
        IPlugin plugin = PLUGIN_MAP.get(connectInfo.getDbType());
        return plugin == null ? null : plugin.getAccountManager();
    }

    public static IRoutineManager getRoutineManager() {
        ConnectInfo connectInfo = getConnectInfo();
        if (connectInfo == null || StringUtils.isBlank(connectInfo.getDbType())) {
            return null;
        }
        IPlugin plugin = PLUGIN_MAP.get(connectInfo.getDbType());
        return plugin == null ? null : plugin.getRoutineManager();
    }

    public static Connection getConnection() {
        return ConnectionPool.getConnection(getConnectInfo());
    }


    public static String getDbVersion() {
        ConnectInfo connectInfo = getConnectInfo();
        String dbVersion = connectInfo.getDbVersion();
        if (dbVersion == null) {
            synchronized (connectInfo) {
                if (connectInfo.getDbVersion() != null) {
                    return connectInfo.getDbVersion();
                } else {
                    dbVersion = DefaultSQLExecutor.getInstance().getDbVersion(getConnection());
                    connectInfo.setDbVersion(dbVersion);
                    return connectInfo.getDbVersion();
                }
            }
        } else {
            return dbVersion;
        }

    }


    public static void putContext(ConnectInfo info) {
        DriverConfig config = info.getDriverConfig();
        if (config == null) {
            config = getDefaultDriverConfig(info.getDbType());
            info.setDriverConfig(config);
        }
        CONNECT_INFO_THREAD_LOCAL.set(info);
    }


    public static void removeContext() {
        ConnectInfo connectInfo = CONNECT_INFO_THREAD_LOCAL.get();
        if (connectInfo != null) {
            CONNECT_INFO_THREAD_LOCAL.remove();
            ConnectionPool.close(connectInfo);
        }
    }

    public static void close() {
        removeContext();
    }

}
