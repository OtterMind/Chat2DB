package ai.chat2db.community.domain.api.model.runtime;

/**
 * Isolation level selected for a console-scoped manual transaction.
 */
public enum TransactionIsolationLevel {
    DEFAULT,
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE
}
