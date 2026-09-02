package ai.chat2db.plugin.clickhouse;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.clickhouse.identifier.ClickHouseIdentifierProcessor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ai.chat2db.plugin.clickhouse.constant.ClickHouseDBManagerConstants.*;
public class ClickHouseDBManager extends DefaultDBManager implements IDbManager {
    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.STREAMING_ONLY;
    }






    private static final Logger log = LoggerFactory.getLogger(ClickHouseDBManager.class);

    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        logDatabaseObjectExportStarted(context, "tables, views, and dictionaries");
        exportTablesOrViewsOrDictionaries(connection, databaseName, schemaName, containData, context);
        logDatabaseObjectExportCompleted(context, "tables, views, and dictionaries");
        logDatabaseObjectExportStarted(context, "functions");
        context.reportProgress(80, EXPORT_TASK_STAGE, "Exporting functions");
        exportFunctions(connection, context);
        logDatabaseObjectExportCompleted(context, "functions");
        context.reportProgress(99, EXPORT_TASK_STAGE, "Database export almost complete");
    }

    private void exportFunctions(Connection connection, TaskExecutionContext context) throws SQLException {
        String sql = "SELECT name,create_query from system.functions where origin='SQLUserDefined'";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(SQL_DROP_FUNCTION_EXISTS).append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(resultSet.getString("name"))).append(";")
                        .append("\n")
                        .append(resultSet.getString("create_query")).append(";").append("\n");
                context.write(sqlBuilder.toString());
            }
        }
    }

    private void exportTablesOrViewsOrDictionaries(Connection connection, String databaseName, String schemaName,
            boolean containData, TaskExecutionContext context) throws SQLException {
        String sql = String.format(SQL_SELECT_CREATE_TABLE_QUERY_HAS, ClickHouseIdentifierProcessor.INSTANCE.escapeString(databaseName));
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {

                String ddl = resultSet.getString("create_table_query");
                boolean dataFlag = resultSet.getInt("has_own_data") == 1;
                String tableType = resultSet.getString("engine");
                String tableOrViewName = resultSet.getString("name");
                if (Objects.equals("View", tableType)) {
                    StringBuilder sqlBuilder = new StringBuilder();
                    sqlBuilder.append(SQL_DROP_VIEW_EXISTS).append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(databaseName)).append(".").append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableOrViewName))
                            .append(";").append("\n").append(ddl).append(";").append("\n");
                    context.write(sqlBuilder.toString());
                } else if (Objects.equals("Dictionary", tableType)) {
                    StringBuilder sqlBuilder = new StringBuilder();
                    sqlBuilder.append(SQL_DROP_DICTIONARY_EXISTS).append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(databaseName)).append(".").append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableOrViewName))
                            .append(";").append("\n").append(ddl).append(";").append("\n");
                    context.write(sqlBuilder.toString());
                } else {
                    StringBuilder sqlBuilder = new StringBuilder();
                    sqlBuilder.append(SQL_DROP_TABLE_EXISTS).append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(databaseName)).append(".").append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableOrViewName))
                            .append(";").append("\n").append(ddl).append(";").append("\n");
                    context.write(sqlBuilder.toString());
                    if (containData && dataFlag) {
                        exportTableData(connection, databaseName, schemaName, tableOrViewName, context);
                    }
                }
            }
        }
    }


    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        String url = setDatabaseInJdbcUrl(connectInfo);
        connectInfo.setUrl(url);

        return super.getConnection(connectInfo);
    }

    private String setDatabaseInJdbcUrl(ConnectInfo connectInfo) {
        String schemaName = connectInfo.getSchemaName();
        String url = connectInfo.getUrl();
        if (StringUtils.isBlank(schemaName)) {
            return url;
        }
        String connectAddress = connectInfo.getHost() + ":" + connectInfo.getPort();
        String[] addressSplit = url.split(connectAddress);
        if(addressSplit == null){
            return url;
        }
        StringBuilder newUrl = new StringBuilder();
        newUrl.append(addressSplit[0]).append(connectAddress).append("/").append(schemaName);
        if (addressSplit.length == 2) {
            if (StringUtils.isNotBlank(addressSplit[1])) {
                String[] param = addressSplit[1].split("\\?");
                if (param.length == 2) {
                    newUrl.append("?").append(param[1]);
                }
            }
        }
        return newUrl.toString();
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "DROP TABLE IF EXISTS " + qualifiedTableName(databaseName, schemaName, tableName, false);
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "TRUNCATE TABLE " + qualifiedTableName(databaseName, schemaName, tableName, true);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        for (String sql : buildCopyTableStatements(databaseName, schemaName, tableName, newTableName, copyData)) {
            DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        }
    }

    static List<String> buildCopyTableStatements(String databaseName, String schemaName, String tableName,
                                                  String newTableName, boolean copyData) {
        String source = qualifiedTableName(databaseName, schemaName, tableName, true);
        String target = qualifiedTableName(databaseName, schemaName, newTableName, true);
        List<String> statements = new ArrayList<>();
        statements.add("CREATE TABLE " + target + " AS " + source);
        if (copyData) {
            statements.add("INSERT INTO " + target + " SELECT * FROM " + source);
        }
        return statements;
    }

    private static String qualifiedTableName(String databaseName, String schemaName, String tableName,
                                             boolean normalizeQuotedTable) {
        String qualifier = StringUtils.isNotBlank(schemaName) ? schemaName : databaseName;
        String normalizedTable = normalizeQuotedTable ? normalizeQuotedIdentifier(tableName) : tableName;
        if (StringUtils.isBlank(qualifier)) {
            return ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(normalizedTable);
        }
        return ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(qualifier)
                + "." + ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(normalizedTable);
    }

    private static String normalizeQuotedIdentifier(String identifier) {
        if (ClickHouseIdentifierProcessor.INSTANCE.isQuoteIdentifier(identifier)) {
            return ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote(identifier);
        }
        return identifier;
    }
}
