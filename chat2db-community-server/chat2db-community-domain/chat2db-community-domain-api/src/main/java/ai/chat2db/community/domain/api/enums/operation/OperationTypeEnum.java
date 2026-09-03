package ai.chat2db.community.domain.api.enums.operation;

/**
 * User-facing operation log categories. Mirrors the frontend
 * {@code OperationTypeEnum} contract in chat2db-community-client.
 */
public enum OperationTypeEnum {

    /**
     * SQL execution history, e.g. the console output panel.
     */
    SQL_EXECUTE,

    /**
     * SQL audit history.
     */
    SQL_AUDIT
}
