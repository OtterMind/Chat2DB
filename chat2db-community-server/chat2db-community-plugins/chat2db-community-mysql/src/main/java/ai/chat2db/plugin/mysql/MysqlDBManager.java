package ai.chat2db.plugin.mysql;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.constant.SQLConstants;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;

import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COPY_TABLE_DATA_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COPY_TABLE_STRUCTURE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_PROCEDURE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_TABLE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_VIEW_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_DATABASE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_TRUNCATE_TABLE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SET_FOREIGN_KEY_CHECKS_DISABLED;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SET_FOREIGN_KEY_CHECKS_ENABLED;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_FUNCTION_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_PROCEDURE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_TABLE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_TRIGGER_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_CREATE_VIEW_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_PROCEDURE_STATUS;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SHOW_TRIGGERS;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ANALYZE_TABLE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_OPTIMIZE_TABLE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_CHECK_TABLE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_REPAIR_TABLE;

import static ai.chat2db.plugin.mysql.constant.MysqlDBManagerConstants.*;
@Slf4j
public class MysqlDBManager extends DefaultDBManager implements IDbManager {

    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {
        asyncContext.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_TABLES_MESSAGE);
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(50);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_VIEWS_MESSAGE);
        exportViews(connection, databaseName, asyncContext);
        asyncContext.setProgress(60);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_PROCEDURES_MESSAGE);
        exportProcedures(connection, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_TRIGGERS_MESSAGE);
        exportTriggers(connection, asyncContext);
        asyncContext.setProgress(90);
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORT_FUNCTIONS_MESSAGE);
        exportFunctions(connection, databaseName, asyncContext);
    }

    private void exportFunctions(Connection connection, String databaseName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getFunctions(databaseName, null, null)) {
            while (resultSet.next()) {
                exportFunction(connection, resultSet.getString(FUNCTION_NAME_COLUMN), asyncContext);
            }

        }
    }

    private void exportFunction(Connection connection, String functionName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SHOW_CREATE_FUNCTION_TEMPLATE, format(functionName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                asyncContext.write(String.format(FUNCTION_TITLE, formatExportTitleValue(functionName)));
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQLConstants.DROP_FUNCTION_IF_EXISTS_SQL_PREFIX).append(format(functionName)).append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR);

                sqlBuilder.append(DELIMITER_BLOCK_START).append(SQLConstants.LINE_SEPARATOR).append(resultSet.getString(CREATE_FUNCTION_COLUMN))
                        .append(ROUTINE_DELIMITER)
                        .append(SQLConstants.LINE_SEPARATOR).append(DELIMITER_BLOCK_END).append(SQLConstants.DOUBLE_LINE_SEPARATOR);
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) throws SQLException {

        asyncContext.write(SQL_SET_FOREIGN_KEY_CHECKS_DISABLED);
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, null, new String[]{TABLE_TYPE, SYSTEM_TABLE_TYPE})) {
            while (resultSet.next()) {
                String tableName = resultSet.getString(TABLE_NAME_COLUMN);
                exportTable(connection, databaseName, schemaName, tableName, asyncContext);
            }
        }
        asyncContext.write(SQL_SET_FOREIGN_KEY_CHECKS_ENABLED);
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SHOW_CREATE_TABLE_TEMPLATE, format(tableName));
        asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORTING_TABLE_MESSAGE + tableName);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                asyncContext.write(String.format(TABLE_TITLE, formatExportTitleValue(tableName)));
                sqlBuilder.append(SQLConstants.DROP_TABLE_IF_EXISTS_SQL_PREFIX).append(format(tableName)).append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR)
                        .append(resultSet.getString(CREATE_TABLE_COLUMN)).append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR);
                asyncContext.write(sqlBuilder.toString());
                if (asyncContext.isContainsData()) {
                    asyncContext.info(DateUtil.formatDateTime(new Date()) + EXPORTING_TABLE_DATA_MESSAGE + tableName);
                    exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
                }
            }
        } catch (Exception e) {
            log.error(EXPORT_TABLE_ERROR_LOG, e);
            asyncContext.error(String.format(EXPORT_TABLE_ERROR_MESSAGE, tableName, e.getMessage()));
        }
    }


    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        exportTableData(connection, databaseName, schemaName, tableName, asyncContext, Integer.MIN_VALUE);
    }

    private void exportViews(Connection connection, String databaseName, AsyncContext asyncContext) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, null, new String[]{VIEW_TYPE})) {
            while (resultSet.next()) {
                exportView(connection, resultSet.getString(TABLE_NAME_COLUMN), asyncContext);
            }
        }
    }

    private void exportView(Connection connection, String viewName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SHOW_CREATE_VIEW_TEMPLATE, format(viewName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                asyncContext.write(String.format(VIEW_TITLE, formatExportTitleValue(viewName)));
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQLConstants.DROP_VIEW_IF_EXISTS_SQL_PREFIX).append(format(viewName)).append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR)
                        .append(resultSet.getString(CREATE_VIEW_COLUMN)).append(SQLConstants.SEMICOLON).append(SQLConstants.DOUBLE_LINE_SEPARATOR);
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportProcedures(Connection connection, AsyncContext asyncContext) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(SQL_SHOW_PROCEDURE_STATUS);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                exportProcedure(connection, resultSet.getString(PROCEDURE_NAME_COLUMN), asyncContext);
            }
        }
    }

    private void exportProcedure(Connection connection, String procedureName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SHOW_CREATE_PROCEDURE_TEMPLATE, format(procedureName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                asyncContext.write(String.format(PROCEDURE_TITLE, formatExportTitleValue(procedureName)));
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQLConstants.DROP_PROCEDURE_IF_EXISTS_SQL_PREFIX).append(format(procedureName)).append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR)
                        .append(DELIMITER_BLOCK_START).append(SQLConstants.LINE_SEPARATOR).append(resultSet.getString(CREATE_PROCEDURE_COLUMN))
                        .append(ROUTINE_DELIMITER)
                        .append(SQLConstants.LINE_SEPARATOR).append(DELIMITER_BLOCK_END).append(SQLConstants.DOUBLE_LINE_SEPARATOR);
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTriggers(Connection connection, AsyncContext asyncContext) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(SQL_SHOW_TRIGGERS);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString(TRIGGER_NAME_COLUMN);
                exportTrigger(connection, triggerName, asyncContext);
            }
        }
    }

    private void exportTrigger(Connection connection, String triggerName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(SQL_SHOW_CREATE_TRIGGER_TEMPLATE, format(triggerName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                asyncContext.write(String.format(TRIGGER_TITLE, formatExportTitleValue(triggerName)));
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQLConstants.DROP_TRIGGER_IF_EXISTS_SQL_PREFIX).append(format(triggerName)).append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR)
                        .append(DELIMITER_BLOCK_START).append(SQLConstants.LINE_SEPARATOR).append(resultSet.getString(ORIGINAL_STATEMENT_COLUMN))
                        .append(ROUTINE_DELIMITER)
                        .append(SQLConstants.LINE_SEPARATOR).append(DELIMITER_BLOCK_END).append(SQLConstants.DOUBLE_LINE_SEPARATOR);
                asyncContext.write(sqlBuilder.toString());
            }
        }
    }

    @Override
    public void updateProcedure(Connection connection, String databaseName, String schemaName, Procedure procedure) throws SQLException {
        try {
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_DROP_PROCEDURE_TEMPLATE, format(procedure.getProcedureName())), resultSet -> {
            });
            String procedureBody = procedure.getProcedureBody();
            DefaultSQLExecutor.getInstance().execute(connection, procedureBody, resultSet -> {
            });
        } catch (Exception e) {
            connection.rollback();
            throw new RuntimeException(e);
        } finally {
            connection.setAutoCommit(true);
        }

    }

    @Override
    public void connectDatabase(Connection connection, String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        try {
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_SELECT_DATABASE_TEMPLATE, format(database)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void dropDatabase(Connection connection, String databaseName) {
        executeDropSql(connection, new MysqlSqlBuilder().ddl().database().buildDropDatabase(databaseName));
    }

    @Override
    public void dropSchema(Connection connection, String databaseName, String schemaName) {
        throw new BusinessException("database.delete.notSupportSchema");
    }

    void executeDropSql(Connection connection, String sql) {
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE_TEMPLATE, format(tableName));
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_TRUNCATE_TABLE_TEMPLATE, format(tableName));
    }

    @Override
    public String analyzeTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return SQL_ANALYZE_TABLE + format(tableName);
    }

    @Override
    public String optimizeTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return SQL_OPTIMIZE_TABLE + format(tableName);
    }

    @Override
    public String checkTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return SQL_CHECK_TABLE + format(tableName);
    }

    @Override
    public String repairTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return SQL_REPAIR_TABLE + format(tableName);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) {
        DefaultSQLExecutor.getInstance().execute(connection, buildCopyTableSql(tableName, newTableName, copyData), resultSet -> null);
    }

    static String buildCopyTableSql(String tableName, String newTableName, boolean copyData) {
        String template = copyData ? SQL_COPY_TABLE_DATA_TEMPLATE : SQL_COPY_TABLE_STRUCTURE_TEMPLATE;
        return String.format(template, format(newTableName), format(tableName));
    }

    static String formatExportTitleValue(String value) {
        return value == null ? null : value.replace('\r', ' ').replace('\n', ' ');
    }

    public static String format(String tableName) {
        return MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        DefaultSQLExecutor.getInstance().execute(connection, buildDropViewSql(databaseName, viewName), resultSet -> null);
    }

    static String buildDropViewSql(String databaseName, String viewName) {
        String qualifiedName = StringUtils.isEmpty(databaseName)
                ? format(viewName)
                : format(databaseName) + SQLConstants.DOT + format(viewName);
        return String.format(SQL_DROP_VIEW_TEMPLATE, qualifiedName);
    }
}
