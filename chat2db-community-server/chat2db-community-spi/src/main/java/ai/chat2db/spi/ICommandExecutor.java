package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.spi.model.request.FetchAllTableRecordsRequest;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Executes SQL commands for a database dialect.
 */
public interface ICommandExecutor {

    /**
     * Executes a command object and returns all execution results.
     *
     * @param sqlExecuteRequest command request containing SQL and execution context.
     * @return execution results produced by the command.
     */
    List<ExecuteResponse> execute(SqlExecuteRequest sqlExecuteRequest);

    /**
     * Executes an update SQL statement.
     *
     * @param connection The database connection to use
     * @param n          update execution option used by the implementation.
     * @return execution result for the update.
     * @throws SQLException when the update fails.
     */
    ExecuteResponse executeUpdate(String sql, Connection connection, int n) throws SQLException;

    /**
     * Executes a command that selects table data.
     *
     * @return execution results containing table rows and metadata.
     */
    List<ExecuteResponse> executeSelectTable(SqlExecuteRequest sqlExecuteRequest);

    /**
     * Executes SQL with optional row limiting.
     *
     * @param sqlStatementExecuteRequest SQL statement execution request.
     * @return execution result for the SQL statement.
     * @throws SQLException when SQL execution fails.
     */
    ExecuteResponse execute(SqlStatementExecuteRequest sqlStatementExecuteRequest) throws SQLException;

    /**
     * Counts the rows returned by a SQL query.
     *
     * @param sql SQL query to count.
     * @param connection active database connection.
     * @return row count returned by the count query.
     * @throws SQLException when count execution fails.
     */
    Long count(String sql, Connection connection) throws SQLException;

    /**
     * Counts the rows returned by a parameterised SQL query, binding the values of
     * its {@code :name} placeholders through JDBC.
     *
     * @param sql SQL query to count, with named placeholders.
     * @param connection active database connection.
     * @param parameters values keyed by placeholder name; empty counts plain SQL.
     * @return row count returned by the count query.
     * @throws SQLException when count execution fails.
     */
    default Long count(String sql, Connection connection, Map<String, SqlParameterValue> parameters)
            throws SQLException {
        if (parameters != null && !parameters.isEmpty()) {
            throw new BusinessException("sqlParameter.unsupportedDatabase");
        }
        return count(sql, connection);
    }

    /**
     * Streams all records from a query to a result-set consumer.
     *
     * @param fetchAllTableRecordsRequest table record streaming request.
     * <p>
     * Typical usage:
     */
    void fetchAllTableRecords(FetchAllTableRecordsRequest fetchAllTableRecordsRequest);

    /**
     * Checks whether SQL should be treated as a query command.
     *
     * @param connection active database connection.
     * @param sql SQL statement to inspect.
     * @return {@code true} when the statement returns rows; otherwise {@code false}.
     */
    boolean isQueryCommand(Connection connection, String sql);
}
