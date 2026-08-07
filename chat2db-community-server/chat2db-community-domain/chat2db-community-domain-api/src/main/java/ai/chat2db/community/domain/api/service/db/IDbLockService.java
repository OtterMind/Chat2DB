package ai.chat2db.community.domain.api.service.db;

import java.util.List;
import java.util.Map;

/**
 * Data and metadata lock inspection with blocking chains (MYSQL-OPS-003). Read-only;
 * uses {@code performance_schema.data_locks/data_lock_waits} on MySQL 8.0 and
 * {@code information_schema.innodb_locks/innodb_lock_waits} on 5.7. The feature never
 * terminates sessions; manual termination is delegated to the session flow (MYSQL-OPS-001).
 */
public interface IDbLockService {

    /**
     * Returns the current lock snapshot.
     *
     * @return view with {@code dataLocks}, {@code waits}, {@code metaLocks}, and
     *         {@code waitChains}; unavailable sources degrade to empty lists.
     */
    Map<String, Object> lockView();
}
