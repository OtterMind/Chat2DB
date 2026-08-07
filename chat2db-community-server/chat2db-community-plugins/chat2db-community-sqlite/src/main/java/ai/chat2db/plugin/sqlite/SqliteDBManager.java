package ai.chat2db.plugin.sqlite;

import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;

import static ai.chat2db.plugin.sqlite.constant.SqliteDBManagerConstants.*;
public class SqliteDBManager extends DefaultDBManager implements IDbManager {






    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        logDatabaseObjectExportStarted(context, "tables");
        exportTables(connection, databaseName, schemaName, containData, context);
        logDatabaseObjectExportCompleted(context, "tables");
        reportExportProgress(context, 50);
        logDatabaseObjectExportStarted(context, "views");
        exportViews(connection, databaseName, context);
        logDatabaseObjectExportCompleted(context, "views");
        reportExportProgress(context, 70);
        logDatabaseObjectExportStarted(context, "triggers");
        exportTriggers(connection, context);
        logDatabaseObjectExportCompleted(context, "triggers");
        reportExportProgress(context, 90);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, null, new String[]{"TABLE", "SYSTEM TABLE"})) {
            while (resultSet.next()) {
                exportTable(connection, databaseName, schemaName, resultSet.getString("TABLE_NAME"), containData,
                        context);
            }
        }
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName,
            boolean containData, TaskExecutionContext context) throws SQLException {
        String sql = String.format(SQL_SELECT_SQL_SQLITE_MASTER_TYPE, SqliteIdentifierProcessor.INSTANCE.escapeString(tableName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_TABLE_EXISTS).append(format(tableName)).append(";").append("\n")
                        .append(resultSet.getString("sql")).append(";").append("\n");
                context.write(sqlBuilder.toString());
                if (containData) {
                    exportTableData(connection, databaseName, schemaName, tableName, context);
                }
            }
        }
    }

    private String format(String tableName) {
        return SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    private void exportViews(Connection connection, String databaseName, TaskExecutionContext context) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                exportView(connection, resultSet.getString("TABLE_NAME"), context);
            }
        }
    }

    private void exportView(Connection connection, String viewName, TaskExecutionContext context) throws SQLException {
        String sql = String.format(SQL_SELECT_SQLITE_MASTER_TYPE_VIEW, SqliteIdentifierProcessor.INSTANCE.escapeString(viewName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_VIEW_EXISTS).append(format(viewName)).append(";").append("\n")
                        .append(resultSet.getString("sql")).append(";").append("\n");
                context.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTriggers(Connection connection, TaskExecutionContext context) throws SQLException {
        String sql = "SELECT * FROM sqlite_master WHERE type = 'trigger';";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString("name");
                exportTrigger(connection, triggerName, context);
            }
        }
    }

    private void exportTrigger(Connection connection, String triggerName, TaskExecutionContext context) throws SQLException {
        String sql = String.format(SQL_SELECT_SQLITE_MASTER_TYPE_TRIGGER, SqliteIdentifierProcessor.INSTANCE.escapeString(triggerName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("sql")).append("\n");
                context.write(sqlBuilder.toString());
            }
        }
    }
}
