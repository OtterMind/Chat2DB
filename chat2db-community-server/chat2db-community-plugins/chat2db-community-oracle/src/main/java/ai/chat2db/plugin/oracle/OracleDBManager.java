package ai.chat2db.plugin.oracle;

import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TriggerMetadataRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;
import java.util.List;

import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;

import static ai.chat2db.plugin.oracle.constant.OracleDBManagerConstants.*;
@Slf4j
public class OracleDBManager extends DefaultDBManager implements IDbManager {



    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting tables");
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(50);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting views");
        exportViews(connection, asyncContext, schemaName);
        asyncContext.setProgress(60);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting producers");
        exportProcedures(connection, schemaName, asyncContext);
        asyncContext.setProgress(90);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + ":Exporting functions");
        exportFunctions(connection, schemaName, asyncContext);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"TABLE", "SYSTEM TABLE"})) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, databaseName, schemaName, tableName, asyncContext);
            }
        }
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        String tableDDL = Chat2DBContext.getDbMetaData().tableDDL(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        String sqlBuilder = "DROP TABLE " + qualifiedName(schemaName, tableName, false) + ";\n" + tableDDL + "\n";
        asyncContext.write(sqlBuilder);
        if (asyncContext.isContainsData()) {
            exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
        }

    }


    private void exportViews(Connection connection, AsyncContext asyncContext, String schemaName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                String viewName = resultSet.getString("TABLE_NAME");
                exportView(connection, asyncContext, schemaName, viewName);
            }
        }
    }

    private void exportView(Connection connection, AsyncContext asyncContext, String schemaName, String viewName) {
        Table view = Chat2DBContext.getDbMetaData().view(connection, new ViewMetadataRequest(null, schemaName, viewName));
        asyncContext.write(view.getDdl() + ";" + "\n");
    }

    private void exportProcedures(Connection connection, String schemaName, AsyncContext asyncContext) {
        List<Procedure> procedures = Chat2DBContext.getDbMetaData().procedures(connection, null, schemaName);
        if (CollectionUtils.isNotEmpty(procedures)) {
            for (Procedure procedure : procedures) {
                String procedureName = procedure.getProcedureName();
                exportProcedure(connection, schemaName, procedureName, asyncContext);
            }
        }

    }

    private void exportProcedure(Connection connection, String schemaName, String procedureName, AsyncContext asyncContext) {
        Procedure procedure = Chat2DBContext.getDbMetaData().procedure(connection,
                new ProcedureMetadataRequest(null, schemaName, procedureName));
        asyncContext.write(procedure.getProcedureBody() + "\n");

    }

    private void exportTriggers(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SELECT_TRIGGER_NAME_ALL_TRIGGERS, OracleIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString("TRIGGER_NAME");
                exportTrigger(connection, schemaName, triggerName, asyncContext);
            }
        }
    }

    private void exportTrigger(Connection connection, String schemaName, String triggerName, AsyncContext asyncContext) {
        Trigger trigger = Chat2DBContext.getDbMetaData().trigger(connection,
                new TriggerMetadataRequest(null, schemaName, triggerName));
        asyncContext.write(trigger.getTriggerBody() + ";" + "\n");

    }

    private void exportFunctions(Connection connection, String schemaName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getFunctions(null, schemaName, null)) {
            while (resultSet.next()) {
                String functionName = resultSet.getString("FUNCTION_NAME");
                exportFunction(connection, schemaName, functionName, asyncContext);
            }
        }
    }

    private void exportFunction(Connection connection, String schemaName, String functionName, AsyncContext asyncContext) {
        Function function = Chat2DBContext.getDbMetaData().function(connection,
                new FunctionMetadataRequest(null, schemaName, functionName));
        asyncContext.write(function.getFunctionBody() + "\n");
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
                    SQL_ALTER_SESSION_SET_CURRENT_SCHEMA
                            + OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName));
        } catch (SQLException e) {
            log.error("connectDatabase error", e);
        }
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        String source = qualifiedName(schemaName, tableName, true);
        String target = qualifiedName(schemaName, newTableName, true);
        String sql;
        if (copyData) {
            sql = "CREATE TABLE " + target + " AS SELECT * FROM " + source;
        } else {
            sql = "CREATE TABLE " + target + " AS SELECT * FROM " + source + " WHERE 1=0";
        }
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "DROP TABLE " + qualifiedName(schemaName, tableName, false);
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "TRUNCATE TABLE " + qualifiedName(schemaName, tableName, true);
    }

    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        exportTableData(connection, databaseName, schemaName, tableName, asyncContext, 10000);
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = "DROP VIEW " + qualifiedName(schemaName, viewName, false);
        DefaultSQLExecutor.getInstance().execute(connection, sql, (resultSet) -> null);
    }

    private static String qualifiedName(String schemaName, String objectName, boolean normalizeQuotedObject) {
        String normalizedObject = normalizeQuotedObject ? normalizeQuotedIdentifier(objectName) : objectName;
        String quotedObject = OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(normalizedObject);
        if (StringUtils.isBlank(schemaName)) {
            return quotedObject;
        }
        return OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "." + quotedObject;
    }

    private static String normalizeQuotedIdentifier(String identifier) {
        if (OracleIdentifierProcessor.INSTANCE.isQuoteIdentifier(identifier)) {
            return OracleIdentifierProcessor.INSTANCE.removeIdentifierQuote(identifier);
        }
        return identifier;
    }
}
