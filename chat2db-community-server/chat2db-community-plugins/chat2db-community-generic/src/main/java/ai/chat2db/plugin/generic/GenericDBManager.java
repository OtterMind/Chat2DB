package ai.chat2db.plugin.generic;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.constant.DBConfigConstants;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;

public class GenericDBManager extends DefaultDBManager implements IDbManager {

    @Override
    public void connectDatabase(Connection connection, String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        String schema = Chat2DBContext.getConnectInfo().getSchemaName();
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        String template = dbConfig.getSql(DBConfigConstants.SQL_CHANGE_DATABASE);
        if (template != null) {
            database = GenericSqlGuards.sanitizeTemplateValue(template, "{database}", database);
            schema = GenericSqlGuards.sanitizeTemplateValue(template, "{schema}", schema);
        }
        String changeDatabase = dbConfig.getChangeDatabase(database, schema);
        if(StringUtils.isEmpty(changeDatabase)){
            return;
        }
        try {
            DefaultSQLExecutor.getInstance().execute(connection, changeDatabase);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
