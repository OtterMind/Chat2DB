package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.config.DBConfig;

import java.util.List;

/**
 * Manages database types defined by the user rather than shipped by a plugin.
 * <p>
 * A user-defined type carries its own JDBC URL template and driver class, which
 * is what lets a database reach the product when its driver cannot be
 * redistributed and no built-in type exists to attach it to.
 */
public interface IDbCustomDatabaseService {

    /**
     * Lists every user-defined database type.
     */
    List<DBConfig> listCustomDatabases();

    /**
     * Returns one user-defined database type, or null when it is not defined.
     */
    DBConfig queryCustomDatabase(String dbType);

    /**
     * Adds or replaces a user-defined database type and registers it so it becomes
     * connectable immediately.
     *
     * @param config must carry a database type, a driver class and a URL template;
     *               the type may not shadow one provided by a plugin.
     */
    void saveCustomDatabase(DBConfig config);

    /**
     * Removes a user-defined database type and unregisters it.
     *
     * @return true if the type existed and was removed.
     */
    boolean deleteCustomDatabase(String dbType);
}
