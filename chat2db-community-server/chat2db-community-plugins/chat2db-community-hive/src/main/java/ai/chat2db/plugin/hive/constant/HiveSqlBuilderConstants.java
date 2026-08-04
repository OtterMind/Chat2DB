package ai.chat2db.plugin.hive.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.hive.enums.type.HiveColumnTypeEnum;
import ai.chat2db.plugin.hive.enums.type.HiveIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.lang3.StringUtils;



public final class HiveSqlBuilderConstants {

    public static final String VALUE_LOCAL_SQL_PART = " \n";
    public static final String VALUE_SINGLE_QUOTE_CLOSE_PAREN_COMMA = "'),\n";
    public static final String SQL_COMMENT_SINGLE_QUOTE = "\r\n COMMENT '";
    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_COMMENT = " COMMENT '";
    public static final String SQL_CREATE_DATABASE = "CREATE DATABASE ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String SQL_RENAME = "RENAME TO ";
    public static final String SQL_SET_TBLPROPERTIES_COMMENT = "SET TBLPROPERTIES ('comment' = ";

    private HiveSqlBuilderConstants() {
    }
}
