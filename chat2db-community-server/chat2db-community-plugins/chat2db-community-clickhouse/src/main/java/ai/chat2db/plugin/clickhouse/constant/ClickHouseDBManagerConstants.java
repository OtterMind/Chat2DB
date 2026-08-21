package ai.chat2db.plugin.clickhouse.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Objects;


public final class ClickHouseDBManagerConstants {

    public static final String SQL_DROP_DICTIONARY_EXISTS = "DROP DICTIONARY IF EXISTS ";
    public static final String SQL_DROP_FUNCTION_EXISTS = "DROP FUNCTION IF EXISTS ";
    public static final String SQL_DROP_TABLE_EXISTS = "DROP TABLE IF EXISTS ";
    public static final String SQL_DROP_VIEW_EXISTS = "DROP VIEW IF EXISTS ";
    public static final String SQL_SELECT_CREATE_TABLE_QUERY_HAS = "SELECT create_table_query, has_own_data,engine,name from system.`tables` WHERE `database`='%s'";

    private ClickHouseDBManagerConstants() {
    }
}
