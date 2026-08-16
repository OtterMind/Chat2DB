package ai.chat2db.plugin.db2;

import ai.chat2db.plugin.db2.constant.SQLConstant;
import ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;

import static ai.chat2db.plugin.db2.constant.DB2DBManagerConstants.*;
@Slf4j
public class DB2DBManager extends DefaultDBManager implements IDbManager {









    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting tables");
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting views");
        exportViews(connection, schemaName, asyncContext);
        asyncContext.setProgress(80);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting producers");
        exportProceduresAndFunctions(connection, schemaName, asyncContext);
        asyncContext.setProgress(90);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting triggers");
        exportTriggers(connection, schemaName, asyncContext);
        asyncContext.setProgress(99);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"TABLE", "SYSTEM TABLE"})) {
            while (resultSet.next()) {
                exportTable(connection, databaseName, schemaName, resultSet.getString("TABLE_NAME"), asyncContext);
            }
        }
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        try {
            DefaultSQLExecutor.getInstance().execute(connection, SQLConstant.TABLE_DDL_FUNCTION_SQL, resultSet -> null);
        } catch (Exception e) {
        }
        String sql = String.format(SQL_SELECT_GENERATE_TABLE_DDL_SQL, Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName), Db2IdentifierProcessor.INSTANCE.escapeString(schemaName), Db2IdentifierProcessor.INSTANCE.escapeString(tableName), Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("sql")).append("\n");
                asyncContext.write(sqlBuilder.toString());
                if (asyncContext.isContainsData()) {
                    exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
                }
            }
        }
    }


    private void exportViews(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_TEXT_SYSCAT_VIEWS_VIEWSCHEMA, Db2IdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String ddl = resultSet.getString("TEXT");
                sqlBuilder.append(ddl).append(";").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportProceduresAndFunctions(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_TEXT_SYSCAT_ROUTINES_ROUTINESCHEMA, Db2IdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String ddl = resultSet.getString("TEXT");
                sqlBuilder.append(ddl).append(";").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }


    private void exportTriggers(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_SYSCAT_TRIGGERS_TRIGSCHEMA, Db2IdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String ddl = resultSet.getString("TEXT");
                sqlBuilder.append(ddl).append(";").append("\n");
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
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_SET_SCHEMA, Db2IdentifierProcessor.escapeIdentifier(schemaName)));
        } catch (SQLException e) {

        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE, Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "TRUNCATE TABLE " + Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,boolean copyData) throws SQLException {
        String sql = String.format(SQL_COPY_TABLE, Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        if(copyData){
            sql = String.format(SQL_INSERT_TABLE_SELECT, Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
            DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        }
    }
}
