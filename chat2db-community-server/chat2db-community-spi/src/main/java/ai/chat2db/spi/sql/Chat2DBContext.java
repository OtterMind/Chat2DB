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
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class Chat2DBContext {
    private static final ThreadLocal<ConnectInfo> CONNECT_INFO_THREAD_LOCAL = new ThreadLocal<>();


    public static Map<String, IPlugin> PLUGIN_MAP = new ConcurrentHashMap<>();

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
                    for (DBConfig config : dbConfigList) {
                        PLUGIN_MAP.put(config.getDbType(), plugin.getPlugin(config));
                    }
                }
            }
        }
    }

    private static IPlugin getPlugin(String dbType) {
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
