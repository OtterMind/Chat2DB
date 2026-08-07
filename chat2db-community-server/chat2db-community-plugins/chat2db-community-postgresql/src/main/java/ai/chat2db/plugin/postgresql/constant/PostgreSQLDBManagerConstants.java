package ai.chat2db.plugin.postgresql.constant;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.Objects;



public final class PostgreSQLDBManagerConstants {

    public static final String SQL_CREATE_REPLACE_VIEW = "CREATE OR REPLACE VIEW ";
    public static final String SQL_CREATE_SEQUENCE = "CREATE SEQUENCE ";
    public static final String SQL_DROP_FUNCTION_EXISTS = "DROP FUNCTION IF EXISTS ";
    public static final String SQL_DROP_PROCEDURE_EXISTS = "DROP PROCEDURE IF EXISTS ";
    public static final String SQL_DROP_SEQUENCE_EXISTS = "DROP SEQUENCE IF EXISTS ";
    public static final String SQL_DROP_TABLE_EXISTS = "DROP TABLE IF EXISTS ";
    public static final String SQL_DROP_TYPE_EXISTS = "DROP TYPE IF EXISTS ";
    public static final String SQL_DROP_VIEW_EXISTS = "DROP VIEW IF EXISTS ";
    public static final String SQL_SET_SEARCH_PATH_USER_PUBLIC = "SET search_path TO %s,\"$user\",\"public\"";

    private PostgreSQLDBManagerConstants() {
    }
}
