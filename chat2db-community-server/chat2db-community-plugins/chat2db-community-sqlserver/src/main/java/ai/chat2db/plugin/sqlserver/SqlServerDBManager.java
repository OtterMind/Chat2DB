package ai.chat2db.plugin.sqlserver;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;

import static ai.chat2db.plugin.sqlserver.constant.SQLConstant.*;
import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;

import static ai.chat2db.plugin.sqlserver.constant.SqlServerDBManagerConstants.*;
@Slf4j
public class SqlServerDBManager extends DefaultDBManager implements IDbManager {








    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) {
        asyncContext.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting tables");
        exportTables(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(50);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting views");
        exportViews(connection, databaseName, schemaName, asyncContext);
        asyncContext.setProgress(60);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting producers");
        exportProcedures(connection, schemaName, asyncContext);
        asyncContext.setProgress(70);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting triggers");
        exportTriggers(connection, schemaName, asyncContext);
        asyncContext.setProgress(80);
        asyncContext.info(DateUtil.formatDateTime(new Date())+":Exporting functions");
        exportFunctions(connection, schemaName, asyncContext);
        asyncContext.setProgress(90);
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ORDER_TABLES_SQL, new String[]{schemaName, schemaName, schemaName}, resultSet -> {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, databaseName, schemaName, tableName, asyncContext);
            }
        });
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        String tableDDL = Chat2DBContext.getDbMetaData().tableDDL(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_DROP_TABLE_EXISTS).append("[").append(tableName).append("]").append(";").append("\ngo\n")
                .append(tableDDL);
        asyncContext.write(sqlBuilder.toString());
        if (asyncContext.isContainsData()) {
            exportTableData(connection, databaseName, schemaName, tableName, asyncContext);
        } else {
            asyncContext.write("go \n");
        }
    }
    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, AsyncContext asyncContext) {
        exportTableData(connection, databaseName, schemaName, tableName, asyncContext, 10000);
        asyncContext.write("go \n");
    }


    private void exportViews(Connection connection, String databaseName, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, VIEWS_DDL_SQL, new String[]{schemaName, databaseName}, resultSet -> {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_VIEW_EXISTS)
                        .append("[").append(resultSet.getString("TABLE_NAME")).append("]")
                        .append(";\n").append("go").append("\n")
                        .append(resultSet.getString("VIEW_DEFINITION")).append(";").append("\n")
                        .append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        });
    }


    private void exportFunctions(Connection connection, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_SQL, new String[]{"FN", schemaName}, resultSet -> {
            while (resultSet.next()) {
                String functionName = resultSet.getString("name");
                exportFunction(connection, functionName, asyncContext);
            }
        });

    }

    private void exportFunction(Connection connection, String functionName, AsyncContext asyncContext) {
        String sql = String.format(ROUTINES_DDL_SQL, "'SQL_SCALAR_FUNCTION', 'SQL_TABLE_VALUED_FUNCTION'", functionName);
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(String.format(DROP_FUNCTION_SQL, functionName, functionName));
                sqlBuilder.append(resultSet.getString("definition"))
                        .append("\n").append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        });
    }


    private void exportProcedures(Connection connection, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_SQL, new String[]{"P", schemaName}, resultSet -> {
            while (resultSet.next()) {
                String procedureName = resultSet.getString("name");
                exportProcedure(connection, procedureName, asyncContext);
            }
        });
    }

    private void exportProcedure(Connection connection, String procedureName, AsyncContext asyncContext) throws SQLException {
        String sql = String.format(ROUTINES_DDL_SQL, "'SQL_STORED_PROCEDURE'", procedureName);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(String.format(DROP_PROCEDURE_SQL, procedureName, procedureName));
                sqlBuilder.append(resultSet.getString("definition")).append("\n").append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());

            }
        }
    }

    private void exportTriggers(Connection connection, String schemaName, AsyncContext asyncContext) {
        DefaultSQLExecutor.getInstance().preExecute(connection, TRIGGERS_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(resultSet.getString("triggerDefinition")).append("\n").append("go").append("\n");
                asyncContext.write(sqlBuilder.toString());
            }
        });
    }

    @Override
    public void connectDatabase(Connection connection, String database) {
        try {
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_USE_DATABASE, database));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        String sourceTable = buildFullTableName(databaseName, schemaName, tableName);
        String targetTable = buildFullTableName(databaseName, schemaName, newTableName);

        // Build CREATE TABLE with explicit column definitions to preserve IDENTITY
        StringBuilder createSql = new StringBuilder();
        createSql.append("CREATE TABLE ").append(targetTable).append(" (\n");

        boolean[] first = {true};
        DefaultSQLExecutor.getInstance().preExecute(connection, SELECT_TABLE_COLUMNS,
                new String[]{schemaName, tableName}, resultSet -> {
                    while (resultSet.next()) {
                        String colName = resultSet.getString("COLUMN_NAME");
                        String dataType = resultSet.getString("DATA_TYPE");
                        boolean isIdentity = resultSet.getBoolean("IS_IDENTITY");
                        boolean isSparse = resultSet.getBoolean("IS_SPARSE");
                        int isNullable = resultSet.getInt("IS_NULLABLE");
                        String defaultValue = resultSet.getString("COLUMN_DEFAULT");
                        String computedDef = resultSet.getString("COMPUTED_DEFINITION");
                        boolean isPersisted = resultSet.getBoolean("IS_PERSISTED");

                        if (!first[0]) {
                            createSql.append(",\n");
                        }
                        first[0] = false;
                        createSql.append("  [").append(colName).append("] ");

                        if (StringUtils.isNotBlank(computedDef)) {
                            createSql.append("AS ").append(computedDef);
                            if (isPersisted) {
                                createSql.append(" PERSISTED");
                            }
                        } else {
                            createSql.append(dataType);
                            if (isIdentity) {
                                int seed = resultSet.getInt("SEED_VALUE");
                                int increment = resultSet.getInt("INCREMENT_VALUE");
                                createSql.append(" IDENTITY(").append(seed).append(",").append(increment).append(")");
                            }
                            if (isSparse) {
                                createSql.append(" SPARSE");
                            }
                            if (isNullable == 1) {
                                createSql.append(" NULL");
                            } else {
                                createSql.append(" NOT NULL");
                            }
                            if (StringUtils.isNotBlank(defaultValue)) {
                                createSql.append(" DEFAULT ").append(defaultValue);
                            }
                        }
                    }
                });

        createSql.append("\n)");
        log.info("copy table DDL: {}", createSql);
        DefaultSQLExecutor.getInstance().execute(connection, createSql.toString(), resultSet -> null);

        if (copyData) {
            String insertSql = "INSERT INTO " + targetTable + " SELECT * FROM " + sourceTable;
            log.info("copy table data: {}", insertSql);
            DefaultSQLExecutor.getInstance().execute(connection, insertSql, resultSet -> null);
        }
    }


    private String buildFullTableName(String databaseName, String schemaName, String tableName) {
        StringBuilder fullTableName = new StringBuilder();

        if (StringUtils.isNotBlank(databaseName)) {
            fullTableName.append("[").append(databaseName).append("].");
        }
        if (StringUtils.isNotBlank(schemaName)) {
            fullTableName.append("[").append(schemaName).append("].");
        }

        if (StringUtils.isNotBlank(tableName)) {
            if (!tableName.startsWith("[") || !tableName.endsWith("]")) {
                fullTableName.append("[").append(tableName).append("]");
            } else {
                fullTableName.append(tableName);
            }
        }

        return fullTableName.toString();
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        String fullTableName = buildFullTableName(databaseName, schemaName, tableName);
        return String.format(SQL_DROP_TABLE, fullTableName);
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        String fullTableName = buildFullTableName(databaseName, schemaName, viewName);
        String sql = String.format(SQL_DROP_VIEW, fullTableName);
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }
}
