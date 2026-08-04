package ai.chat2db.plugin.mongodb;

import java.sql.Connection;
import java.sql.SQLException;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.util.StringUtils;

import static ai.chat2db.plugin.mongodb.constant.MongodbDBManagerConstants.*;
public class MongodbDBManager extends DefaultDBManager implements IDbManager {





    @Override
    public void connectDatabase(Connection connection, String database) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            return;
        }
        String schemaName = connectInfo.getSchemaName();
        if (StringUtils.isEmpty(schemaName)) {
            return;
        }
        try {
            DefaultSQLExecutor.getInstance().execute(connection,
                    String.format(SCRIPT_USE_SCHEMA, MongodbSqlGuards.requireDatabaseName(schemaName)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SCRIPT_DROP_COLLECTION, MongodbSqlGuards.collectionAccessor(tableName));
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) throws SQLException {
        return String.format(SCRIPT_TRUNCATE_COLLECTION, MongodbSqlGuards.collectionAccessor(tableName));
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,boolean copyData) throws SQLException {
        String sql = String.format(SCRIPT_COPY_COLLECTION, MongodbSqlGuards.collectionAccessor(newTableName),
            MongodbSqlGuards.collectionAccessor(tableName));
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

}
