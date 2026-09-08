package ai.chat2db.plugin.postgresql;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.spi.util.FileUtils;

public class PostgreSQLPlugin extends PgsqlSyntaxPlugin implements IPlugin {

    private DBConfig dbConfig;

    @Override
    public DBConfig getDBConfig() {
        if(dbConfig != null) {
            return dbConfig;
        }
        dbConfig = FileUtils.readJsonValue(this.getClass(), "pg.json", DBConfig.class);
        return dbConfig;
    }

    @Override
    public IDbMetaData getDbMetaData() {
        return new PostgreSQLMetaData();
    }

    @Override
    public IDbManager getDbManager() {
        return new PostgreSQLDBManager(true);
    }
}
