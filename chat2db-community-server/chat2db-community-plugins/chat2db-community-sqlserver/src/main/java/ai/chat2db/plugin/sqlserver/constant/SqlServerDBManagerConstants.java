package ai.chat2db.plugin.sqlserver.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;



public final class SqlServerDBManagerConstants {

    public static final String SQL_DROP_TABLE_EXISTS = "DROP TABLE IF EXISTS ";
    public static final String SQL_DROP_VIEW_EXISTS = "DROP VIEW IF EXISTS ";
    public static final String SQL_COPY_TABLE_DATA = "INSERT INTO %s SELECT * FROM %s";
    public static final String SQL_COPY_TABLE_DATA_WITH_COLUMNS = "INSERT INTO %s (%s) SELECT %s FROM %s";
    public static final String SQL_SET_IDENTITY_INSERT = "SET IDENTITY_INSERT %s %s";
    public static final String SQL_DROP_TABLE = "DROP TABLE %s";
    public static final String SQL_DROP_VIEW = "DROP VIEW %s";
    public static final String SQL_USE_DATABASE = "use [%s];";

    private SqlServerDBManagerConstants() {
    }
}
