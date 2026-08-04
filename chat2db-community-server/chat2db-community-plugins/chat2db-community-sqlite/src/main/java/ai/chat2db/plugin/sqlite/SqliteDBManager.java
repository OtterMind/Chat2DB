package ai.chat2db.plugin.sqlite;

import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import cn.hutool.core.date.DateUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;

import static ai.chat2db.plugin.sqlite.constant.SqliteDBManagerConstants.*;
public class SqliteDBManager extends DefaultDBManager implements IDbManager {






    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting tables");
        exportTables(connection, databaseName, schemaName,asyncContext);
        asyncContext.setProgress(50);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting views");
        exportViews(connection, databaseName, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting triggers");
        exportTriggers(connection, asyncContext);
        asyncContext.setProgress(90);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, null, new String[]{"TABLE", "SYSTEM TABLE"})) {
            while (resultSet.next()) {
                exportTable(connection, databaseName,schemaName, resultSet.getString("TABLE_NAME"), asyncContext);
            }
        }
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_SQL_SQLITE_MASTER_TYPE, SqliteIdentifierProcessor.INSTANCE.escapeString(tableName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_TABLE_EXISTS).append(format(tableName)).append(";").append("\n")
                        .append(resultSet.getString("sql")).append(";").append("\n");
                asyncContext.write(sqlBuilder.toString());
                if (asyncContext.isContainsData()) {
                    exportTableData(connection, databaseName,schemaName, tableName, asyncContext);
                }
            }
        }
    }

    private String format(String tableName) {
        return SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    private void exportViews(Connection connection, String databaseName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                exportView(connection, resultSet.getString("TABLE_NAME"), asyncContext);
            }
        }
    }

    private void exportView(Connection connection, String viewName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_SQLITE_MASTER_TYPE_VIEW, SqliteIdentifierProcessor.INSTANCE.escapeString(viewName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_VIEW_EXISTS).append(format(viewName)).append(";").append("\n")
                        .append(resultSet.getString("sql")).append(";").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTriggers(Connection connection, AsyncContext asyncContext) throws SQLException {
        String sql = "SELECT * FROM sqlite_master WHERE type = 'trigger';";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString("name");
                exportTrigger(connection, triggerName, asyncContext);
            }
        }
    }

    private void exportTrigger(Connection connection, String triggerName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_SQLITE_MASTER_TYPE_TRIGGER, SqliteIdentifierProcessor.INSTANCE.escapeString(triggerName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("sql")).append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }
}
