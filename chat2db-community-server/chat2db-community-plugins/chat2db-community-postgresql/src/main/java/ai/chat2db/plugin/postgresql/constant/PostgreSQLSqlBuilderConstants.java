package ai.chat2db.plugin.postgresql.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.postgresql.PostgreSQLMetaData;
import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLColumnTypeEnum;
import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLIndexTypeEnum;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
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



public final class PostgreSQLSqlBuilderConstants {

    public static final String SQL_WHERE_CTID_IN_OPEN_PAREN_SELECT_CTID_FROM = " where ctid in (select ctid from ";
    public static final String VALUE_LIMIT_1_CLOSE_PAREN = " limit 1)";
    public static final String VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE = " IS '";
    public static final String VALUE_DOUBLE_QUOTE = " ";
    public static final String VALUE_DOUBLE_QUOTE_TO_DOUBLE_QUOTE = "\" TO \"";
    public static final String VALUE_DOUBLE_QUOTE_2 = " \n";
    public static final String VALUE_LC_CTYPE_EQUAL_SINGLE_QUOTE = "\n LC_CTYPE = '";
    public static final String VALUE_SINGLE_QUOTE = "' ";
    public static final String SQL_LC_COLLATE_EQUAL_SINGLE_QUOTE = "\n LC_COLLATE = '";
    public static final String SQL_SEMICOLON_COMMENT_ON_DATABASE_DOUBLE_QUOTE = "; COMMENT ON DATABASE ";
    public static final String SQL_SEMICOLON_COMMENT_ON_SCHEMA_DOUBLE_QUOTE = "; COMMENT ON SCHEMA ";
    public static final String SQL_RECURSIVE = "RECURSIVE ";
    public static final String UNDEFINED_KEYWORD = "undefined";
    public static final String SQL_ALTER_TABLE = "ALTER TABLE ";
    public static final String SQL_COMMENT_TABLE = "COMMENT ON TABLE";
    public static final String SQL_COMMENT_VIEW = "comment on view";
    public static final String SQL_CREATE = "CREATE ";
    public static final String SQL_CREATE_DATABASE = "CREATE DATABASE ";
    public static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA ";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";
    public static final String SQL_LIMIT = " LIMIT ";
    public static final String SQL_OFFSET = " OFFSET ";
    public static final String SQL_RENAME = "RENAME TO ";
    public static final String SQL_RENAME_COLUMN = "RENAME COLUMN \"";
    public static final String SQL_REPLACE = "OR REPLACE ";
    public static final String DROP_DATABASE_SQL = "DROP DATABASE %s";
    public static final String DROP_SCHEMA_SQL = "DROP SCHEMA %s";

    private PostgreSQLSqlBuilderConstants() {
    }
}
