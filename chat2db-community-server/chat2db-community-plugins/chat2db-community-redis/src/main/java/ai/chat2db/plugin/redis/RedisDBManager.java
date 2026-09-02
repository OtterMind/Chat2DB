package ai.chat2db.plugin.redis;

import ai.chat2db.plugin.redis.constant.RedisConstants;
import ai.chat2db.plugin.redis.util.RedisValueUtils;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultSQLExecutor;

import java.sql.Connection;
import java.sql.SQLException;

public class RedisDBManager extends DefaultDBManager implements IDbManager {
    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.NONE;
    }


    @Override
    public void connectDatabase(Connection connection, String database) {
        try {
            DefaultSQLExecutor.getInstance().execute(connection, RedisConstants.COMMAND_SELECT_DATABASE_PREFIX + database);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return RedisConstants.COMMAND_DELETE_TABLE_PREFIX + RedisValueUtils.getRedisValue(tableName);
    }

}
