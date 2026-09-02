package ai.chat2db.plugin.sundb;

import java.sql.*;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.sundb.identifier.SUNDBIdentifierProcessor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static ai.chat2db.plugin.sundb.constant.SUNDBDBManagerConstants.*;
public class SUNDBDBManager extends DefaultDBManager implements IDbManager {
    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.STREAMING_ONLY;
    }








    private String format(String tableName) {
        return SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }




    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        logDatabaseObjectExportStarted(context, "tables");
        exportTables(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "tables");
        context.reportProgress(50, EXPORT_TASK_STAGE, "Exporting tables");
        logDatabaseObjectExportStarted(context, "views");
        exportViews(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "views");
        context.reportProgress(60, EXPORT_TASK_STAGE, "Exporting views");
        logDatabaseObjectExportStarted(context, "procedures");
        exportProcedures(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "procedures");
        context.reportProgress(70, EXPORT_TASK_STAGE, "Exporting procedures");
        logDatabaseObjectExportStarted(context, "triggers");
        exportTriggers(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "triggers");
        context.reportProgress(90, EXPORT_TASK_STAGE, "Exporting triggers");
    }

    private void exportTables(Connection connection, String schemaName, TaskExecutionContext context) throws SQLException {
        String sql =String.format(SQL_SELECT_TABLE_NAME_ALL_TABLES, SUNDBIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, tableName, schemaName, context);
            }
        }
    }


    private void exportTable(Connection connection, String tableName, String schemaName, TaskExecutionContext context)
            throws SQLException {


    }

    private void exportTableColumnComment(Connection connection, String schemaName, String tableName, StringBuilder sqlBuilder) throws SQLException {
          String sql =String.format(SQL_SELECT_COLNAME_COMMENT_SYS_SYSCOLUMNCOMMENTS +
                                            "where SCHNAME = '%s' and TVNAME = '%s'and TABLE_TYPE = 'TABLE';", SUNDBIdentifierProcessor.INSTANCE.escapeString(schemaName), SUNDBIdentifierProcessor.INSTANCE.escapeString(tableName));
          try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
              while (resultSet.next()) {
                  String columnName = resultSet.getString("COLNAME");
                  String comment = resultSet.getString("COMMENT$");
                      sqlBuilder.append(SQL_COMMENT_COLUMN).append(format(schemaName)).append(".").append(format(tableName))
                              .append(".").append(format(columnName)).append(" IS ").append("'").append(SUNDBIdentifierProcessor.INSTANCE.escapeString(comment)).append("';").append("\n");
              }
          }
    }


    private void exportViews(Connection connection, String schemaName, TaskExecutionContext context) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                String viewName = resultSet.getString("TABLE_NAME");
                exportView(connection, viewName, schemaName, context);
            }
        }
    }

    private void exportView(Connection connection, String viewName, String schemaName, TaskExecutionContext context)
            throws SQLException {


    }

    private void exportProcedures(Connection connection, String schemaName, TaskExecutionContext context)
            throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getProcedures(null, schemaName, null)) {
            while (resultSet.next()) {
                String procedureName = resultSet.getString("PROCEDURE_NAME");
                exportProcedure(connection, schemaName, procedureName, context);
            }
        }
    }

    private void exportProcedure(Connection connection, String schemaName, String procedureName,
            TaskExecutionContext context) throws SQLException {
        String sql = String.format(ROUTINES_SQL,"PROC", SUNDBIdentifierProcessor.INSTANCE.escapeString(schemaName), SUNDBIdentifierProcessor.INSTANCE.escapeString(procedureName));
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("TEXT")).append("\n");
                context.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTriggers(Connection connection, String schemaName, TaskExecutionContext context)
            throws SQLException {


    }

    private void exportTrigger(Connection connection, String schemaName, String triggerName, StringBuilder sqlBuilder) throws SQLException {
        String sql = String.format(TRIGGER_SQL, SUNDBIdentifierProcessor.INSTANCE.escapeString(schemaName), SUNDBIdentifierProcessor.INSTANCE.escapeString(triggerName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                sqlBuilder.append(resultSet.getString("TRIGGER_BODY")).append("\n");
            }
        }
    }

    @Override
    public void connectDatabase(Connection connection, String database) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            return;
        }
        String schemaName = connectInfo.getSchemaName();
        try {
            DefaultSQLExecutor.getInstance().execute(connection,
                    String.format(SQL_SET_SCHEMA, SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)));
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE_EXISTS,
                SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName),
                SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,boolean copyData) throws SQLException {
        String sql;
        if(copyData){
            sql = String.format(SQL_COPY_TABLE_DATA, SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        }else {
            sql = String.format(SQL_COPY_TABLE_STRUCTURE, SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        }
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

}
