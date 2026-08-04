package ai.chat2db.plugin.mongodb.constant;

import java.sql.Connection;
import java.sql.SQLException;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.util.StringUtils;


public final class MongodbDBManagerConstants {

    public static final String SCRIPT_COPY_COLLECTION = "db.%s.insertMany(db.%s.find({}))";
    public static final String SCRIPT_DROP_COLLECTION = "db.%s.drop()";
    public static final String SCRIPT_TRUNCATE_COLLECTION = "db.%s.deleteMany({})";
    public static final String SCRIPT_USE_SCHEMA = "use %s;";

    private MongodbDBManagerConstants() {
    }
}
