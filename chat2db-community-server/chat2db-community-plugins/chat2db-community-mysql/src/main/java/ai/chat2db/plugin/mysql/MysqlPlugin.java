package ai.chat2db.plugin.mysql;

import ai.chat2db.plugin.mysql.account.MysqlAccountManager;
import ai.chat2db.plugin.mysql.diff.MysqlDiffChangeSetProcessor;
import ai.chat2db.spi.IAccountManager;
import ai.chat2db.spi.IDbDiffChangeSetProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.IRoutineManager;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.util.FileUtils;

public class MysqlPlugin extends MysqlSyntaxPlugin implements IPlugin {

    private static final IDbDiffChangeSetProcessor DIFF_CHANGE_SET_PROCESSOR =
            new MysqlDiffChangeSetProcessor();

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
        return new MysqlMetaData();
    }

    @Override
    public IDbManager getDbManager() {
        return new MysqlDBManager();
    }

    @Override
    public IDbDiffChangeSetProcessor getDiffChangeSetProcessor() {
        return DIFF_CHANGE_SET_PROCESSOR;
    }

    @Override
    public IAccountManager getAccountManager() {
        return new MysqlAccountManager();
    }

    @Override
    public IRoutineManager getRoutineManager() {
        return new MysqlRoutineManager();
    }
}
