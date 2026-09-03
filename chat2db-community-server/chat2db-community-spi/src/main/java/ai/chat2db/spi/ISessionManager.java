package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Provides dialect-specific database session inspection and termination.
 */
public interface ISessionManager {

    List<Map<String, Object>> list(Connection connection, String databaseVersion);

    DbSessionKillResult kill(Connection connection, String databaseVersion, String connectionUser,
                             Long connectionId, String killType);
}
