package ai.chat2db.plugin.xugudb;

import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.Date;

import static ai.chat2db.plugin.xugudb.constant.XUGUDBManagerConstants.*;
public class XUGUDBManager extends DefaultDBManager implements IDbManager {







    private String format(String tableName) {
        return XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }






    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException{

        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting tables");
        exportTables(connection, schemaName, asyncContext);
        asyncContext.setProgress(50);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting views");
        exportViews(connection, schemaName, asyncContext);
        asyncContext.setProgress(60);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting producers");
        exportProcedures(connection, schemaName, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting triggers");
        exportTriggers(connection, schemaName, asyncContext);
        asyncContext.setProgress(90);

    }

    private void exportTables(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_TABLE_NAME_ALL_TABLES, XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, tableName, schemaName, asyncContext);
            }
        }
    }


    private void exportTable(Connection connection, String tableName, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = """
                SELECT
                    (SELECT comments FROM user_tab_comments WHERE table_name = '%s') AS comments,
                    (SELECT dbms_metadata.get_ddl('TABLE', '%s', '%s') FROM dual) AS ddl
                FROM dual;
                """;
        try (PreparedStatement statement = connection.prepareStatement(String.format(sql, XugudbIdentifierProcessor.INSTANCE.escapeString(tableName), XugudbIdentifierProcessor.INSTANCE.escapeString(tableName), XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName))); ResultSet resultSet = statement.executeQuery()) {
            String formatSchemaName = format(schemaName);
            String formatTableName = format(tableName);
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_TABLE_EXISTS).append(formatSchemaName).append(".").append(formatTableName)
                        .append(";").append("\n")
                        .append(resultSet.getString("ddl")).append("\n");
                String comment = resultSet.getString("comments");
                if (StringUtils.isNotBlank(comment)) {
                    sqlBuilder.append(SQL_COMMENT_TABLE).append(formatSchemaName).append(".").append(formatTableName)
                            .append(" IS ").append("'").append(XugudbIdentifierProcessor.INSTANCE.escapeString(comment)).append("';");
                }
                asyncContext.write(sqlBuilder.toString());
                exportTableColumnComment(connection, schemaName, tableName, asyncContext);
            }
            if (asyncContext.isContainsData()) {
                exportTableData(connection,null, schemaName, tableName, asyncContext);
            }
        }
    }

    private void exportTableColumnComment(Connection connection, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_COLNAME_COMMENT_SYS_SYSCOLUMNCOMMENTS +
                "where SCHNAME = '%s' and TVNAME = '%s'and TABLE_TYPE = 'TABLE';", XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName), XugudbIdentifierProcessor.INSTANCE.escapeString(tableName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLNAME");
                String comment = resultSet.getString("COMMENT$");
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_COMMENT_COLUMN).append(format(schemaName)).append(".").append(format(tableName))
                        .append(".").append(format(columnName)).append(" IS ").append("'").append(XugudbIdentifierProcessor.INSTANCE.escapeString(comment)).append("';").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }


    private void exportViews(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                String viewName = resultSet.getString("TABLE_NAME");
                exportView(connection, viewName, schemaName, asyncContext);
            }
        }
    }

    private void exportView(Connection connection, String viewName, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_DBMS_METADATA_GET_DDL, XugudbIdentifierProcessor.INSTANCE.escapeString(viewName), XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("ddl")).append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportProcedures(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getProcedures(null, schemaName, null)) {
            while (resultSet.next()) {
                String procedureName = resultSet.getString("PROCEDURE_NAME");
                exportProcedure(connection, schemaName, procedureName, asyncContext);
            }
        }
    }

    private void exportProcedure(Connection connection, String schemaName, String procedureName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(ROUTINES_SQL, "PROC", XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName), XugudbIdentifierProcessor.INSTANCE.escapeString(procedureName));
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("TEXT")).append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTriggers(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(TRIGGER_SQL_LIST, XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString("TRIGGER_NAME");
                exportTrigger(connection, schemaName, triggerName, asyncContext);
            }
        }
    }

    private void exportTrigger(Connection connection, String schemaName, String triggerName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(TRIGGER_SQL, XugudbIdentifierProcessor.INSTANCE.escapeString(schemaName), XugudbIdentifierProcessor.INSTANCE.escapeString(triggerName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("TRIGGER_BODY")).append("\n");
                asyncContext.write(sqlBuilder.toString());
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
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_SET_SCHEMA, format(schemaName)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE_EXISTS, format(tableName));
    }
}
