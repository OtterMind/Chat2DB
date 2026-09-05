package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.DBConfig;
import java.util.List;

/**
 * Entry point implemented by each database plugin.
 */
public interface IPlugin {

    /**
     * Returns the primary database configuration supported by this plugin.
     *
     * @return default database configuration for this plugin.
     */
    DBConfig getDBConfig();

    /**
     * Returns the metadata provider for this plugin.
     *
     * @return metadata provider used to query catalogs, tables, columns, and routines.
     */
    default IDbMetaData getDbMetaData() {
        return new DefaultMetaService();
    }

    /**
     * Returns the database manager for connection and DDL operations.
     *
     * @return database manager implemented by the plugin.
     */
    default IDbManager getDbManager() {
        return new DefaultDBManager();
    }

    /**
     * Returns the SQL builder for this plugin.
     *
     * @return dialect SQL builder.
     */
    default ISqlBuilder getSqlBuilder() {
        return getDbMetaData().getSqlBuilder();
    }

    /**
     * Returns the value processor for this plugin.
     *
     * @return dialect value processor.
     */
    default IValueProcessor getValueProcessor() {
        return getDbMetaData().getValueProcessor();
    }

    /**
     * Returns the identifier processor for this plugin.
     *
     * @return dialect identifier processor.
     */
    default ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return getDbMetaData().getSQLIdentifierProcessor();
    }

    /**
     * Returns the command executor for this plugin.
     *
     * @return dialect command executor.
     */
    default ICommandExecutor getCommandExecutor() {
        return getDbMetaData().getCommandExecutor();
    }

    /**
     * Returns key operations for this plugin.
     *
     * @return key operations implementation.
     */
    default IKeyOperations getKeyOperations() {
        return getDbMetaData().keyOperations();
    }

    /**
     * Returns the optional SQL syntax provider for this plugin.
     *
     * @return syntax provider when supported; otherwise {@code null}.
     */
    default ISqlSyntaxPlugin getSqlSyntaxPlugin() {
        if (this instanceof ISqlSyntaxPlugin sqlSyntaxPlugin) {
            return sqlSyntaxPlugin;
        }
        return null;
    }

    /**
     * Returns the optional account manager for this plugin.
     *
     * @return account manager when supported; otherwise {@code null}.
     */
    default IAccountManager getAccountManager() {
        return null;
    }

    /**
     * Returns the optional routine manager for this plugin.
     *
     * @return routine manager when supported; otherwise {@code null}.
     */
    default IRoutineManager getRoutineManager() {
        return null;
    }

    /**
     * Returns the optional active transaction manager for this plugin.
     *
     * @return active transaction manager when supported; otherwise {@code null}.
     */
    default IActiveTransactionManager getActiveTransactionManager() {
        return null;
    }

    /**
     * Returns the optional lock inspection manager for this plugin.
     *
     * @return lock manager when supported; otherwise {@code null}.
     */
    default ILockManager getLockManager() {
        return null;
    }

    /**
     * Returns all database configurations supported by this plugin instance.
     *
     * @return supported database configurations, or an empty list when the plugin only exposes {@link #getDBConfig()}.
     */
    default List<DBConfig> getDBConfigList() {
        return List.of();
    }

    /**
     * Resolves the concrete plugin implementation for a database configuration.
     *
     * @param dbConfig database configuration selected by the caller.
     * @return plugin instance that should handle the configuration.
     */
    default IPlugin getPlugin(DBConfig dbConfig) {
        return this;
    }

}
