package ai.chat2db.spi;

import java.sql.Connection;

import ai.chat2db.community.domain.api.model.lock.LockView;

/**
 * Provides dialect-specific database lock inspection.
 */
public interface ILockManager {

    LockView lockView(Connection connection, Long dataSourceId);
}
