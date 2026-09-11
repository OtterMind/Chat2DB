package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;

import java.sql.Connection;
import java.util.List;

/**
 * Provides dialect-specific active transaction inspection.
 */
public interface IActiveTransactionManager {

    List<ActiveTransaction> activeTransactions(Connection connection);
}
