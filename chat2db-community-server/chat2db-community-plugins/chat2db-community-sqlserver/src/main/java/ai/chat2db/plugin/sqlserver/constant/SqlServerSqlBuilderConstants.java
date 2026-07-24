package ai.chat2db.plugin.sqlserver.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.sqlserver.enums.type.SqlServerColumnTypeEnum;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerIndexTypeEnum;
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
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;


public final class SqlServerSqlBuilderConstants {

    public static final String SQL_DELETE = "DELETE ";
    public static final String SQL_DELETE_TOP_OPEN_PAREN_1_CLOSE_PAREN = "DELETE TOP (1) ";
    public static final String SQL_UPDATE_TOP_OPEN_PAREN_1_CLOSE_PAREN = "UPDATE TOP (1) ";
    public static final String VALUE_CLOSE_BRACKET_OPEN_PAREN = "] (";
    public static final String VALUE_CLOSE_PAREN_GO = "\n)\ngo\n";
    public static final String VALUE_GO = "\ngo\n";
    public static final String VALUE_BACKSLASH_DOT = "\\.";
    public static final String ORDER_BY_KEYWORD_LOWER = "order by";
    public static final String SQL_ORDER_BY_OPEN_PAREN_SELECT_NULL_CLOSE_PAREN = "\n ORDER BY (SELECT NULL)";
    public static final String SQL_COLLATE = " COLLATE ";
    public static final String SQL_EXEC_OPEN_BRACKET = "exec [";
    public static final String VALUE_CLOSE_BRACKET_DOT_SYS_DOT_SP_ADDEXTENDEDPROPERTY_SINGLE_QUOTE_MS_DESCRIPTION = "].sys. sp_addextendedproperty 'MS_Description','";
    public static final String VALUE_CLOSE_BRACKET_GO = "] \ngo\n";
    public static final String SQL_EXEC_SP_ADDEXTENDEDPROPERTY_SINGLE_QUOTE_MS_DESCRIPTION_SINGLE_QUOTE_COMMA_SINGLE_QUOTE = "exec sp_addextendedproperty 'MS_Description','";
    public static final String SQL_COMMA_SINGLE_QUOTE_SCHEMA_SINGLE_QUOTE = ",'SCHEMA'";
    public static final String VALUE_COMMA_SINGLE_QUOTE = ",'";
    public static final String UNDEFINED_KEYWORD = "undefined";
    public static final String SQL_EXEC_SP_ADDEXTENDEDPROPERTY_SINGLE_QUOTE_MS_DESCRIPTION_SINGLE_QUOTE_COMMA = "exec sp_addextendedproperty 'MS_Description',";
    public static final String SQL_SINGLE_QUOTE_SCHEMA_SINGLE_QUOTE = "'SCHEMA'";
    public static final String SQL_SINGLE_QUOTE_VIEW_SINGLE_QUOTE = "'VIEW'";
    public static final String SQL_SET_SHOWPLAN_XML_ON_SEMICOLON_GO = "SET SHOWPLAN_XML ON;\nGO\n";
    public static final String SQL_GO_SET_SHOWPLAN_XML_OFF_SEMICOLON = "\nGO\nSET SHOWPLAN_XML OFF;";
    public static final String SQL_ALTER = "OR ALTER ";
    public static final String SQL_CREATE = "CREATE ";
    public static final String SQL_CREATE_DATABASE = "CREATE DATABASE [";
    public static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA [";
    public static final String SQL_CREATE_TABLE = "CREATE TABLE ";

    public static final String INDEX_COMMENT_SCRIPT = "exec sp_addextendedproperty 'MS_Description','%s','SCHEMA','%s','TABLE','%s','INDEX','%s' \ngo\n";
    public static final String TABLE_COMMENT_SCRIPT = "exec sp_addextendedproperty 'MS_Description','%s','SCHEMA','%s','TABLE','%s' \ngo\n";
    public static final String COLUMN_COMMENT_SCRIPT = "exec sp_addextendedproperty 'MS_Description','%s','SCHEMA','%s','TABLE','%s','COLUMN','%s' \ngo\n";
    public static final String UPDATE_TABLE_COMMENT_SCRIPT = "exec sp_updateextendedproperty 'MS_Description','%s','SCHEMA','%s','TABLE','%s' \ngo\n";
    public static final String RENAME_TABLE_SCRIPT = "exec sp_rename '%s','%s','OBJECT' \ngo\n";

    // ROW_NUMBER pagination for SQL Server < 2012
    public static final String SQL_ROW_NUMBER_PREFIX = "SELECT * FROM (SELECT TMP_PAGE.*, ROW_NUMBER() OVER(ORDER BY (SELECT NULL)) AS CAHT2DB_AUTO_ROW_ID FROM (\n";
    public static final String SQL_ROW_NUMBER_SUFFIX = "\n) TMP_PAGE) TMP_PAGE WHERE CAHT2DB_AUTO_ROW_ID BETWEEN ";
    public static final String SQL_AND = " AND ";


    private SqlServerSqlBuilderConstants() {
    }
}
