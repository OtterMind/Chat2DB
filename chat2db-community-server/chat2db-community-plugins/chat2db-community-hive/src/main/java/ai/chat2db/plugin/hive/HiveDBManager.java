package ai.chat2db.plugin.hive;

import java.sql.Connection;
import java.sql.SQLException;

import ai.chat2db.plugin.hive.identifier.HiveIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.springframework.util.StringUtils;

import static ai.chat2db.plugin.hive.constant.HiveDBManagerConstants.*;
public class HiveDBManager extends DefaultDBManager implements IDbManager {





    @Override
    public void connectDatabase(Connection connection, String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        try {
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_USE_DATABASE, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE_EXISTS, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,boolean copyData) throws SQLException {
        String sql = String.format(SQL_COPY_TABLE, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        if(copyData){
            sql = String.format(SQL_INSERT_TABLE_SELECT, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
            DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        }
    }
}
