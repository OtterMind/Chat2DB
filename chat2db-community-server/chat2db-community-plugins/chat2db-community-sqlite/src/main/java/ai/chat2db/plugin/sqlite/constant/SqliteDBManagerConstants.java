package ai.chat2db.plugin.sqlite.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import cn.hutool.core.date.DateUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;


public final class SqliteDBManagerConstants {

    public static final String SQL_DROP_TABLE_EXISTS = "DROP TABLE IF EXISTS ";
    public static final String SQL_DROP_VIEW_EXISTS = "DROP VIEW IF EXISTS ";
    public static final String SQL_SELECT_SQLITE_MASTER_TYPE_TRIGGER = "SELECT * FROM sqlite_master WHERE type = 'trigger' and name='%s';";
    public static final String SQL_SELECT_SQLITE_MASTER_TYPE_VIEW = "SELECT * FROM sqlite_master WHERE type = 'view' and name='%s';";
    public static final String SQL_SELECT_SQL_SQLITE_MASTER_TYPE = "SELECT sql FROM sqlite_master WHERE type='table' AND name='%s'";

    private SqliteDBManagerConstants() {
    }
}
