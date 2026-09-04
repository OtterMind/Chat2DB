package ai.chat2db.community.domain.api.model.runtime;

import lombok.Data;

import java.util.List;

/**
 * Snapshot of a console's manual-transaction state, returned to the frontend so it can keep
 * the toolbar (Commit/Rollback controls, status indicator) in sync and tell the user whether
 * a commit/rollback cleanly resolved the transaction or its outcome is unknown.
 */
@Data
public class TransactionStateResponse {

    /**
     * Whether the console currently has an open (uncommitted) transaction.
     */
    private boolean inTransaction;

    /**
     * Transaction mode the console is operating in: {@code manual} when auto-commit is off,
     * {@code auto} otherwise.
     */
    private TransactionMode mode;

    /**
     * Outcome of the most recent commit/rollback/release operation, or null when none has
     * occurred. {@code UNKNOWN} signals that the server could not confirm whether the
     * transaction was rolled back (e.g. commit failed and the connection was discarded).
     */
    private String outcome;

    /**
     * Last error message associated with the transaction, if any (e.g. a failed commit).
     */
    private String lastError;

    /**
     * Isolation level applied to the bound manual transaction.
     */
    private TransactionIsolationLevel isolationLevel;

    /**
     * Isolation levels supported by the current datasource's JDBC driver.
     */
    private List<TransactionIsolationLevel> supportedIsolationLevels = List.of();

    public static TransactionStateResponse of(boolean inTransaction, TransactionMode mode) {
        return of(inTransaction, mode, TransactionIsolationLevel.DEFAULT);
    }

    public static TransactionStateResponse of(
            boolean inTransaction,
            TransactionMode mode,
            TransactionIsolationLevel isolationLevel
    ) {
        return of(inTransaction, mode, isolationLevel, List.of());
    }

    public static TransactionStateResponse of(
            boolean inTransaction,
            TransactionMode mode,
            TransactionIsolationLevel isolationLevel,
            List<TransactionIsolationLevel> supportedIsolationLevels
    ) {
        TransactionStateResponse response = new TransactionStateResponse();
        response.inTransaction = inTransaction;
        response.mode = mode == null ? TransactionMode.AUTO : mode;
        response.isolationLevel = isolationLevel == null ? TransactionIsolationLevel.DEFAULT : isolationLevel;
        response.supportedIsolationLevels = supportedIsolationLevels == null
                ? List.of()
                : List.copyOf(supportedIsolationLevels);
        return response;
    }
}
