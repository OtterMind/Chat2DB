package ai.chat2db.plugin.mysql;

import ai.chat2db.plugin.mysql.account.MysqlAccountManager;
import ai.chat2db.plugin.mysql.lock.MysqlLockManager;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.result.ResultSetEditorMetadata;
import ai.chat2db.spi.IAccountManager;
import ai.chat2db.spi.IActiveTransactionManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.ILockManager;
import ai.chat2db.spi.IRoutineManager;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.util.FileUtils;

public class MysqlPlugin extends MysqlSyntaxPlugin implements IPlugin {

    private DBConfig dbConfig;

    @Override
    public DBConfig getDBConfig() {
        if (dbConfig != null) {
            return dbConfig;
        }
        dbConfig = FileUtils.readJsonValue(this.getClass(), "mysql.json", DBConfig.class);
        return dbConfig;
    }

    @Override
    public IDbMetaData getDbMetaData() {
        return new NativeMysqlMetaData();
    }

    @Override
    public IDbManager getDbManager() {
        return new MysqlDBManager();
    }

    @Override
    public IAccountManager getAccountManager() {
        return new MysqlAccountManager();
    }

    @Override
    public IRoutineManager getRoutineManager() {
        return new MysqlRoutineManager();
    }

    @Override
    public IActiveTransactionManager getActiveTransactionManager() {
        return new MysqlActiveTransactionManager();
    }

    @Override
    public ILockManager getLockManager() {
        return new MysqlLockManager();
    }

    private static final class NativeMysqlMetaData extends MysqlMetaData {

        @Override
        public ResultSetEditorMetadata resolveResultSetEditorMetadata(TableColumn column) {
            return resolveMysqlResultSetEditorMetadata(column);
        }
    }
}
