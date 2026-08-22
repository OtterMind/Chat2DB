package ai.chat2db.plugin.snowflake;

import ai.chat2db.plugin.snowflake.identifier.SnowflakeIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeDBManagerConstants.*;
public class SnowflakeDBManager extends DefaultDBManager implements IDbManager {

    private static final String DATABASE_PROPERTY = "db";
    private static final String SCHEMA_PROPERTY = "schema";
    private static final String QUERY_RESULT_FORMAT_PROPERTY = "JDBC_QUERY_RESULT_FORMAT";
    private static final String QUERY_RESULT_FORMAT_JSON = "JSON";




    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        connectInfo.setExtendInfo(prepareExtendInfo(connectInfo));
        return super.getConnection(connectInfo);
    }

    static List<KeyValue> prepareExtendInfo(ConnectInfo connectInfo) {
        List<KeyValue> configuredExtendInfo = connectInfo.getExtendInfo();
        // Preserve the same DriverConfig fallback that ConnectInfo.getExtendMap() uses before adding Snowflake values.
        if ((configuredExtendInfo == null || configuredExtendInfo.isEmpty()) && connectInfo.getDriverConfig() != null) {
            configuredExtendInfo = connectInfo.getDriverConfig().getExtendInfo();
        }
        List<KeyValue> extendInfo = configuredExtendInfo == null
                ? new ArrayList<>()
                : new ArrayList<>(configuredExtendInfo);
        replacePropertyIfConfigured(extendInfo, DATABASE_PROPERTY, connectInfo.getDatabaseName());
        replacePropertyIfConfigured(extendInfo, SCHEMA_PROPERTY, connectInfo.getSchemaName());
        replaceProperty(extendInfo, QUERY_RESULT_FORMAT_PROPERTY, QUERY_RESULT_FORMAT_JSON);
        return extendInfo;
    }

    private static void replacePropertyIfConfigured(List<KeyValue> extendInfo, String propertyName, String propertyValue) {
        // Always drop the managed entry first so a reused ConnectInfo cannot keep a stale value after it is cleared.
        removeProperty(extendInfo, propertyName);
        if (StringUtils.isNotBlank(propertyValue)) {
            addProperty(extendInfo, propertyName, propertyValue);
        }
    }

    private static void replaceProperty(List<KeyValue> extendInfo, String propertyName, String propertyValue) {
        removeProperty(extendInfo, propertyName);
        addProperty(extendInfo, propertyName, propertyValue);
    }

    private static void removeProperty(List<KeyValue> extendInfo, String propertyName) {
        extendInfo.removeIf(keyValue -> keyValue != null
                && StringUtils.equalsIgnoreCase(propertyName, keyValue.getKey()));
    }

    private static void addProperty(List<KeyValue> extendInfo, String propertyName, String propertyValue) {
        KeyValue keyValue = new KeyValue();
        keyValue.setKey(propertyName);
        keyValue.setValue(propertyValue);
        extendInfo.add(keyValue);
    }


    @Override
    public void connectDatabase(Connection connection, String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            try {
                DefaultSQLExecutor.getInstance().execute(connection,
                        String.format(SQL_USE_DATABASE, SnowflakeIdentifierProcessor.escapeIdentifier(database)));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        } else {
            try {
                DefaultSQLExecutor.getInstance().execute(connection,
                        String.format(SQL_USE_SCHEMA, SnowflakeIdentifierProcessor.escapeIdentifier(database),
                                SnowflakeSqlGuards.requireSnowflakeName(connectInfo.getSchemaName(), "schema name")));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE, format(tableName));
    }

    public static String format(String tableName) {
        return SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

}
