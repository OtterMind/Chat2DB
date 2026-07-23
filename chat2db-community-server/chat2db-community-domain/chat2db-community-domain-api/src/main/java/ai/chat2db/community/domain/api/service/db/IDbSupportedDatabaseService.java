package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.config.SupportedDatabaseSummary;

import java.util.List;

/**
 * Provides the inventory of database types registered by plugins.
 */
public interface IDbSupportedDatabaseService {

    /**
     * Lists all database types currently registered, sorted by display name.
     *
     * @return one summary per registered database type.
     */
    List<SupportedDatabaseSummary> listSupportedDatabases();
}
