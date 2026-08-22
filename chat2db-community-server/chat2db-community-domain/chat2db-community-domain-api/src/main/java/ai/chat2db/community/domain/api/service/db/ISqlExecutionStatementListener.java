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

    /**
     * Notifies that a statement which can implicitly commit the current transaction is about
     * to execute while a manual transaction is open. Listeners (e.g. the streaming job) use
     * this to surface a warning to the user; execution proceeds regardless.
     *
     * @param sql the statement SQL that would implicitly commit.
     */
    default void onImplicitCommitWarning(String sql) {
    }
}
