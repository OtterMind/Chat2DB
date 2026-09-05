package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.lock.LockView;

/**
 * Read-only data and metadata lock inspection with blocking chains. Dialect-specific
 * query and parsing behavior is provided by the current database plugin.
 */
public interface IDbLockService {

    /**
     * Returns the current lock snapshot for the requested datasource.
     *
     * @return typed view with lock rows, sessions, wait chains, and per-section errors.
     */
    LockView lockView(Long dataSourceId);
}
