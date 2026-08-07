package ai.chat2db.plugin.db2.constant;

import ai.chat2db.plugin.db2.constant.SQLConstant;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;


public final class DB2DBManagerConstants {

    public static final String SQL_SELECT_GENERATE_TABLE_DDL_SQL = "select %s.GENERATE_TABLE_DDL('%s', '%s') as sql from %s;";
    public static final String SQL_SELECT_SYSCAT_TRIGGERS_TRIGSCHEMA = "select * from SYSCAT.TRIGGERS where TRIGSCHEMA = '%s';";
    public static final String SQL_SELECT_TEXT_SYSCAT_ROUTINES_ROUTINESCHEMA = "select TEXT from syscat.routines where ROUTINESCHEMA='%s';";
    public static final String SQL_SELECT_TEXT_SYSCAT_VIEWS_VIEWSCHEMA = "select TEXT from syscat.views where VIEWSCHEMA='%s';";
    public static final String SQL_SET_SCHEMA = "SET SCHEMA \"%s\"";
    public static final String SQL_DROP_TABLE = "DROP TABLE %s";
    public static final String SQL_COPY_TABLE = "CREATE TABLE %s LIKE %s INCLUDING INDEXES";
    public static final String SQL_INSERT_TABLE_SELECT = "INSERT INTO %s SELECT * FROM %s";

    private DB2DBManagerConstants() {
    }
}
