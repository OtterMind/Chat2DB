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
import java.util.List;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeDBManagerConstants.*;
public class SnowflakeDBManager extends DefaultDBManager implements IDbManager {





    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        List<KeyValue> extendInfo = connectInfo.getExtendInfo();
        if (StringUtils.isNotBlank(connectInfo.getDatabaseName())) {
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("db");
            keyValue.setValue(connectInfo.getDatabaseName());
            extendInfo.add(keyValue);
        }
        if (StringUtils.isNotBlank(connectInfo.getSchemaName())) {
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("schema");
            keyValue.setValue(connectInfo.getSchemaName());
            extendInfo.add(keyValue);
        }
        KeyValue keyValue = new KeyValue();
        keyValue.setKey("JDBC_QUERY_RESULT_FORMAT");
        keyValue.setValue("JSON");
        extendInfo.add(keyValue);
        connectInfo.setExtendInfo(extendInfo);
        return super.getConnection(connectInfo);
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
