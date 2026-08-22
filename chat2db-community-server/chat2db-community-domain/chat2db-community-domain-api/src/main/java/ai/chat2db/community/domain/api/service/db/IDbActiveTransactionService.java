package ai.chat2db.community.domain.api.service.db;

import java.util.List;
import java.util.Map;

/**
 * Inspects active InnoDB transactions (MYSQL-OPS-002). Read-only; requires
 * {@code PROCESS} for full visibility of other users' transactions and SQL text.
 */
public interface IDbActiveTransactionService {

    /**
     * Lists active InnoDB transactions with their state, age, isolation level, lock
     * counters, owning thread, user, host, database, and current SQL (when visible).
     *
     * @return a list of transaction maps, empty when no transaction is active.
     */
    List<Map<String, Object>> activeTransactions();
}
