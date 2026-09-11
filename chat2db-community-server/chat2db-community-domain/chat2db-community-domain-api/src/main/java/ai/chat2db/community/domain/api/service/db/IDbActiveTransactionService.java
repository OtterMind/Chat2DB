package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;

import java.util.List;

/**
 * Inspects active InnoDB transactions (MYSQL-OPS-002). Read-only; requires
 * {@code PROCESS} for full visibility of other users' transactions and SQL text.
 */
public interface IDbActiveTransactionService {

    /**
     * Lists active InnoDB transactions with their state, age, isolation level, lock
     * counters, owning thread, user, host, database, and current SQL (when visible).
     *
     * @return active transactions, empty when no transaction is active.
     */
    List<ActiveTransaction> activeTransactions();
}
