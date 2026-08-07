package ai.chat2db.plugin.kingbase;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;

import static ai.chat2db.plugin.kingbase.constant.KingBaseDBManagerConstants.*;
@Slf4j
public class KingBaseDBManager extends DefaultDBManager implements IDbManager {



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
            String sql = String.format(SQL_SET_SEARCH_PATH_USER_PUBLIC,
                    KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName));
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
        return "DROP TABLE IF EXISTS " + qualifiedTableName(schemaName, tableName);
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,boolean copyData) throws SQLException {
        String sql = buildCopyTableSql(schemaName, tableName, newTableName, copyData);
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    static String buildCopyTableSql(String schemaName, String tableName, String newTableName,
                                    boolean copyData) {
        return "CREATE TABLE " + qualifiedTableName(schemaName, newTableName)
                + " AS TABLE " + qualifiedTableName(schemaName, tableName)
                + (copyData ? " WITH DATA" : " WITH NO DATA");
    }

    private static String qualifiedTableName(String schemaName, String tableName) {
        String quotedTable = KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
        if (StringUtils.isBlank(schemaName)) {
            return quotedTable;
        }
        return KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)
                + "." + quotedTable;
    }

    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
            TaskExecutionContext context) {
        exportTableData(connection, databaseName, schemaName, tableName, context, 10000);
    }
}
