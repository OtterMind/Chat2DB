package ai.chat2db.plugin.clickhouse.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseColumnTypeEnum;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.lang3.StringUtils;



public final class ClickHouseSqlBuilderConstants {

    public static final String SQL_MODIFY_COMMENT = "MODIFY COMMENT";
    public static final String SQL_SEMICOLON_ALTER_DATABASE = ";ALTER DATABASE ";
    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_COMMENT = " COMMENT '";
    public static final String SQL_CREATE_DATABASE = "CREATE DATABASE ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";

    private ClickHouseSqlBuilderConstants() {
    }
}
