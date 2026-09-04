package ai.chat2db.community.domain.api.service.db;

import java.sql.Statement;


public interface ISqlExecutionStatementListener {

    /**
     * Notifies that a JDBC statement has been created.
     *
     * @param statement created JDBC statement.
     */
    void onStatementCreated(Statement statement);

    /**
     * Notifies that a JDBC statement has been closed.
     *
     * @param statement closed JDBC statement.
     */
    void onStatementClosed(Statement statement);

    default void onImplicitCommitWarning(String sql) {
    }

}
