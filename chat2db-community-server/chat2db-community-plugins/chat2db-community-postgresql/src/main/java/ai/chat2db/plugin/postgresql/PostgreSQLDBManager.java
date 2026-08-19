package ai.chat2db.plugin.postgresql;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.Objects;

import static ai.chat2db.plugin.postgresql.constant.SqlConstant.*;
import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;

import static ai.chat2db.plugin.postgresql.constant.PostgreSQLDBManagerConstants.*;
@Slf4j
public class PostgreSQLDBManager extends DefaultDBManager implements IDbManager {

    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        context.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        logDatabaseObjectExportStarted(context, "types");
        exportTypes(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "types");
        logDatabaseObjectExportStarted(context, "sequences");
        exportSequences(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "sequences");
        logDatabaseObjectExportStarted(context, "tables");
        exportTables(connection, databaseName, schemaName, containData, context);
        logDatabaseObjectExportCompleted(context, "tables");
        reportExportProgress(context, 50);
        logDatabaseObjectExportStarted(context, "views");
        exportViews(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "views");
        reportExportProgress(context, 60);
        logDatabaseObjectExportStarted(context, "routines");
        exportRoutines(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "routines");
        reportExportProgress(context, 90);
        logDatabaseObjectExportStarted(context, "triggers");
        exportTriggers(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "triggers");
    }


    private void exportSequences(Connection connection, String schemaName, TaskExecutionContext context) {
        DefaultSQLExecutor.getInstance().preExecute(connection, SEQUENCES_SQL, new String[]{schemaName}, resultSet -> {
            StringBuilder sqlBuilder = new StringBuilder(150);
            while (resultSet.next()) {
                String sequenceName = resultSet.getString("SEQUENCE_NAME");
                String startValue = resultSet.getString("START_VALUE");
                String incrementBy = resultSet.getString("INCREMENT_BY");
                String maxValue = resultSet.getString("MAX_VALUE");
                String minValue = resultSet.getString("MIN_VALUE");
                String cacheSize = resultSet.getString("CACHE_SIZE");
                boolean isCycled = resultSet.getBoolean("IS_CYCLED");
                if (StringUtils.isBlank(sequenceName)) {
                    continue;
                }
                String quotedSequenceName = qualifiedTableName(schemaName, sequenceName, false);
                sqlBuilder.append(SQL_DROP_SEQUENCE_EXISTS).append(quotedSequenceName).append(";\n");
                sqlBuilder.append(SQL_CREATE_SEQUENCE).append(quotedSequenceName).append("\n")
                        .append(" START WITH ").append(startValue).append("\n")
                        .append(" INCREMENT BY ").append(incrementBy).append("\n")
                        .append(" MAXVALUE ").append(maxValue).append("\n")
                        .append(" MINVALUE ").append(minValue).append("\n")
                        .append(" CACHE ").append(cacheSize).append("\n")
                        .append(isCycled ? " CYCLE" : "NO CYCLE").append("\n")
                        .append(";\n");

                context.write(sqlBuilder.toString());
                sqlBuilder.setLength(0);
            }

        });
    }

    private void exportTypes(Connection connection, String schemaName, TaskExecutionContext context) {
        StringBuilder typeBuilder = new StringBuilder();
        DefaultSQLExecutor.getInstance().preExecute(connection, ENUM_TYPE_DDL_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                typeBuilder.append(SQL_DROP_TYPE_EXISTS)
                        .append(qualifiedTableName(schemaName, resultSet.getString("type_name"), false))
                        .append(";\n");
                typeBuilder.append(resultSet.getString("ddl")).append("\n");
                context.write(typeBuilder.toString());
            }
        });
        typeBuilder.setLength(0);
        DefaultSQLExecutor.getInstance().preExecute(connection, UDT_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                String typeName = qualifiedTableName(schemaName, resultSet.getString("type_name"), false);
                typeBuilder.append(SQL_DROP_TYPE_EXISTS).append(typeName).append(";\n");
                typeBuilder.append(resultSet.getString("create_type_statement")).append("\n");
                context.write(typeBuilder.toString());
                typeBuilder.setLength(0);
            }
        });
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) {
        DefaultSQLExecutor.getInstance().preExecute(connection, TABLES_SQL, new String[]{schemaName, schemaName}, resultSet -> {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, databaseName, schemaName, tableName, containData, context);
            }
        });
    }

    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName,
            boolean containData, TaskExecutionContext context) {
        String tableDDL = Chat2DBContext.getDbMetaData().tableDDL(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("\n").append(SQL_DROP_TABLE_EXISTS)
                .append(qualifiedTableName(schemaName, tableName, false)).append(";").append("\n")
                .append(tableDDL).append("\n");
        context.write(sqlBuilder.toString());
        if (containData) {
            exportTableData(connection, databaseName, schemaName, tableName, context);
        }


    }


    private void exportViews(Connection connection, String schemaName, TaskExecutionContext context) throws SQLException {
        DefaultSQLExecutor.getInstance().preExecute(connection, VIEWS_DDL_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String viewName = resultSet.getString("table_name");
                String viewDefinition = resultSet.getString("view_definition");
                String quotedObjectName = qualifiedTableName(schemaName, viewName, false);
                sqlBuilder.append(SQL_DROP_VIEW_EXISTS).append(quotedObjectName).append(";\n");
                sqlBuilder.append(SQL_CREATE_REPLACE_VIEW).append(quotedObjectName).append(" AS ").append(viewDefinition).append("\n");
                context.write(sqlBuilder.toString());
            }
        });

    }

    private void exportRoutines(Connection connection, String schemaName, TaskExecutionContext context) {
        DefaultSQLExecutor.getInstance().preExecute(connection, ROUTINES_DDL_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                String routineName = resultSet.getString("proname");
                String routineDefinition = resultSet.getString("function_definition");
                String prokind = resultSet.getString("prokind");
                if (Objects.equals("f", prokind)) {
                    sqlBuilder.append(SQL_DROP_FUNCTION_EXISTS).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)).append(".").append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(routineName)).append(";\n");
                } else {
                    sqlBuilder.append(SQL_DROP_PROCEDURE_EXISTS).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)).append(".").append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(routineName)).append(";\n");
                }
                sqlBuilder.append(routineDefinition).append(";\n\n");
                context.write(sqlBuilder.toString());
                sqlBuilder.setLength(0);
            }
        });
    }

    private void exportTriggers(Connection connection, String schemaName, TaskExecutionContext context) {
        DefaultSQLExecutor.getInstance().preExecute(connection, TRIGGERS_DDL_SQL, new String[]{schemaName}, resultSet -> {
            while (resultSet.next()) {
                context.write(resultSet.getString("trigger_definition") + ";\n");
            }
        });

    }

    @Override
    public void connectDatabase(Connection connection, String database) {

    }

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        String url = connectInfo.getUrl();
        String database = connectInfo.getDatabaseName();
        if (database != null && !database.isEmpty()) {
            url = replaceDatabaseInJdbcUrl(url, database);
        }
        connectInfo.setUrl(url);
        String schemaName = connectInfo.getSchemaName();
        connectInfo.setSchemaName(null);
        Connection connection = super.getConnection(connectInfo);
        if (StringUtils.isNotBlank(schemaName)) {
            String sql = String.format(SQL_SET_SEARCH_PATH_USER_PUBLIC, PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName));
            try {
                DefaultSQLExecutor.getInstance().execute(connection, sql);
            } catch (SQLException e) {
                log.error("connectDatabase error", e);
            }
            connectInfo.setSchemaName(schemaName);
        }
        return connection;

    }


    public String replaceDatabaseInJdbcUrl(String url, String newDatabase) {
        String[] urlAndParams = url.split("\\?");
        String urlWithoutParams = urlAndParams[0];
        String[] parts = urlWithoutParams.split("/");
        parts[parts.length - 1] = newDatabase;
        String newUrlWithoutParams = String.join("/", parts);
        String newUrl = urlAndParams.length > 1 ? newUrlWithoutParams + "?" + urlAndParams[1] : newUrlWithoutParams;

        return newUrl;
    }


    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "DROP TABLE " + qualifiedTableName(schemaName, tableName, false);
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "TRUNCATE TABLE " + qualifiedTableName(schemaName, tableName, true);
    }

    @Override
    public void dropDatabase(Connection connection, String databaseName) {
        executeDropSql(connection, new PostgreSQLSqlBuilder().ddl().database().buildDropDatabase(databaseName));
    }

    @Override
    public void dropSchema(Connection connection, String databaseName, String schemaName) {
        executeDropSql(connection, new PostgreSQLSqlBuilder().ddl().schema().buildDropSchema(schemaName));
    }

    void executeDropSql(Connection connection, String sql) {
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        DefaultSQLExecutor.getInstance().execute(connection,
                buildCopyTableSql(schemaName, tableName, newTableName, copyData), resultSet -> null);
    }

    static String buildCopyTableSql(String schemaName, String tableName, String newTableName,
                                    boolean copyData) {
        String source = qualifiedTableName(schemaName, tableName, true);
        String target = qualifiedTableName(schemaName, newTableName, true);
        return "CREATE TABLE " + target + " AS TABLE " + source
                + (copyData ? " WITH DATA" : " WITH NO DATA");
    }

    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, TaskExecutionContext context) {
        exportTableData(connection, databaseName, schemaName, tableName, context, 10000);
    }


    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = "DROP VIEW " + qualifiedTableName(schemaName, viewName, false);
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    private static String qualifiedTableName(String schemaName, String tableName,
                                             boolean normalizeQuotedTable) {
        String normalizedTable = normalizeQuotedTable ? normalizeQuotedIdentifier(tableName) : tableName;
        String quotedTable = PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(normalizedTable);
        if (StringUtils.isBlank(schemaName)) {
            return quotedTable;
        }
        return PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)
                + "." + quotedTable;
    }

    private static String normalizeQuotedIdentifier(String identifier) {
        if (PostgreSQLIdentifierProcessor.INSTANCE.isQuoteIdentifier(identifier)) {
            return PostgreSQLIdentifierProcessor.INSTANCE.removeIdentifierQuote(identifier);
        }
        return identifier;
    }
}
