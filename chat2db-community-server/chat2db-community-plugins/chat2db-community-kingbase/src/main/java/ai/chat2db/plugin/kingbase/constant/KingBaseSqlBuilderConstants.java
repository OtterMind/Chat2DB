package ai.chat2db.plugin.kingbase.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.kingbase.enums.type.KingBaseColumnTypeEnum;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



public final class KingBaseSqlBuilderConstants {

    public static final String SQL_TABLESPACE_DOUBLE_QUOTE = " TABLESPACE \"";
    public static final String SQL_TABLESPACE_DOUBLE_QUOTE_SYS_DEFAULT_DOUBLE_QUOTE_SEMICOLON = " TABLESPACE \"SYS_DEFAULT\";";
    public static final String VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE = "\" IS '";
    public static final String VALUE_DOUBLE_QUOTE = "\" ";
    public static final String VALUE_DOUBLE_QUOTE_TO_DOUBLE_QUOTE = "\" TO \"";
    public static final String VALUE_DOUBLE_QUOTE_2 = "\" \n";
    public static final String SYSTEM_KEYWORD = "SYSTEM";
    public static final String SQL_WITH_OWNER_EQUAL_DOUBLE_QUOTE = " WITH  OWNER = \"";
    public static final String SQL_ENCODING = " ENCODING  ";
    public static final String SQL_AUTHORIZATION_DOUBLE_QUOTE = " AUTHORIZATION \"";
    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_COMMENT_DATABASE = "COMMENT ON DATABASE ";
    public static final String SQL_COMMENT_TABLE = "COMMENT ON TABLE";
    public static final String SQL_CREATE_DATABASE = "CREATE DATABASE ";
    public static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String SQL_LIMIT = " LIMIT ";
    public static final String SQL_OFFSET = " OFFSET ";
    public static final String SQL_RENAME = "RENAME TO ";
    public static final String SQL_RENAME_COLUMN = "RENAME COLUMN \"";

    private KingBaseSqlBuilderConstants() {
    }
}
