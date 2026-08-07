package ai.chat2db.community.domain.api.service.db;

import java.util.List;
import java.util.Map;

/**
 * Exposes database session inspection and termination contracts.
 */
public interface IDbSessionService {

    /**
     * Lists active database sessions.
     *
     * @return list of session maps with keys: id, user, host, db, command, time, state, info.
     */
    List<Map<String, Object>> list();

    /**
     * Terminates a database session or query.
     *
     * @param connectionId connection ID to terminate.
     * @param killType "QUERY" to stop only the current query, "CONNECTION" to disconnect.
     */
    void kill(Long connectionId, String killType);
}
