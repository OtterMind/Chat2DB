package ai.chat2db.plugin.xugudb.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.Date;


public final class XUGUDBManagerConstants {

    public static final String SQL_COMMENT_COLUMN = "COMMENT ON COLUMN ";
    public static final String SQL_COMMENT_TABLE = "COMMENT ON TABLE ";
    public static final String SQL_DROP_TABLE_EXISTS = "DROP TABLE IF EXISTS %s";
    public static final String SQL_SET_SCHEMA = "SET SCHEMA TO %s";
    public static final String SQL_SELECT_COLNAME_COMMENT_SYS_SYSCOLUMNCOMMENTS = "select COLNAME,COMMENT$ from SYS.SYSCOLUMNCOMMENTS\n";
    public static final String SQL_SELECT_DBMS_METADATA_GET_DDL = "SELECT DBMS_METADATA.GET_DDL('VIEW','%s','%s') as ddl FROM DUAL;";
    public static final String SQL_SELECT_TABLE_NAME_ALL_TABLES = "SELECT TABLE_NAME FROM ALL_TABLES where OWNER='%s' and TABLESPACE_NAME='MAIN'";
    public static final String ROUTINES_SQL = "SELECT OWNER, NAME, TEXT FROM ALL_SOURCE WHERE TYPE = '%s' AND OWNER = '%s' AND NAME = '%s' ORDER BY LINE";
    public static final String TRIGGER_SQL_LIST = "SELECT OWNER, TRIGGER_NAME FROM ALL_TRIGGERS WHERE OWNER = '%s'";
    public static final String TRIGGER_SQL = "SELECT OWNER, TRIGGER_NAME, TABLE_OWNER, TABLE_NAME, TRIGGERING_TYPE, TRIGGERING_EVENT, STATUS, TRIGGER_BODY "
            + "FROM ALL_TRIGGERS WHERE OWNER = '%s' AND TRIGGER_NAME = '%s'";


    private XUGUDBManagerConstants() {
    }
}
