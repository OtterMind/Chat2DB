package ai.chat2db.plugin.mysql.constant;

import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.plugin.mysql.enums.MysqlViewAlgorithmOptionEnum;
import ai.chat2db.plugin.mysql.enums.MysqlViewCheckOptionEnum;
import ai.chat2db.plugin.mysql.enums.MysqlViewSqlSecurityOptionEnum;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.plugin.mysql.enums.type.*;
import ai.chat2db.plugin.mysql.value.MysqlValueProcessor;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.enums.plugin.ResultSetEditorTypeEnum;
import ai.chat2db.spi.DefaultMetaService;
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
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IResultSetFunction;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;



public final class MysqlMetaDataConstants {

    public static final String SHOW_CHARACTER_SET_SQL = "SHOW CHARACTER SET";
    public static final String SHOW_COLLATION_SQL = "SHOW COLLATION";
    public static final String SHOW_ENGINES_SQL = "SHOW ENGINES";
    public static final String FIELD_CHARSET = "Charset";
    public static final String FIELD_DEFAULT_COLLATION = "Default collation";
    public static final String FIELD_COLLATION = "Collation";
    public static final String FIELD_SUPPORT = "Support";
    public static final String FIELD_ENGINE = "Engine";
    public static final String FIELD_AUTO_INCREMENT = "AUTO_INCREMENT";
    public static final String FIELD_CARDINALITY = "Cardinality";
    public static final String FIELD_CHARACTER_SET_NAME = "CHARACTER_SET_NAME";
    public static final String FIELD_COLLATION_NAME = "COLLATION_NAME";
    public static final String FIELD_COLUMN_COMMENT = "COLUMN_COMMENT";
    public static final String FIELD_COLUMN_DEFAULT = "COLUMN_DEFAULT";
    public static final String FIELD_COLUMN_KEY = "COLUMN_KEY";
    public static final String FIELD_COLUMN_NAME = "Column_name";
    public static final String FIELD_COLUMN_NAME_UPPER = "COLUMN_NAME";
    public static final String FIELD_COLUMN_TYPE = "COLUMN_TYPE";
    public static final String FIELD_CREATE_FUNCTION = "Create Function";
    public static final String FIELD_CREATE_PROCEDURE = "Create Procedure";
    public static final String FIELD_CREATE_TABLE = "Create Table";
    public static final String FIELD_CREATE_TIME = "CREATE_TIME";
    public static final String FIELD_CREATE_VIEW = "Create View";
    public static final String FIELD_DATA_LENGTH = "DATA_LENGTH";
    public static final String FIELD_DATA_TYPE = "DATA_TYPE";
    public static final String FIELD_ENGINE_UPPER = "ENGINE";
    public static final String FIELD_EXTRA = "EXTRA";
    public static final String FIELD_INDEX_COMMENT = "Index_comment";
    public static final String FIELD_INDEX_COMMENT_FALLBACK = "Comment";
    public static final String FIELD_INDEX_TYPE = "Index_type";
    public static final String FIELD_IS_NULLABLE = "IS_NULLABLE";
    public static final String FIELD_KEY_NAME = "Key_name";
    public static final String FIELD_NAME = "Name";
    public static final String FIELD_NON_UNIQUE = "Non_unique";
    public static final String FIELD_NUMERIC_SCALE = "NUMERIC_SCALE";
    public static final String FIELD_ORDINAL_POSITION = "ORDINAL_POSITION";
    public static final String FIELD_ROUTINE_COMMENT = "ROUTINE_COMMENT";
    public static final String FIELD_SEQ_IN_INDEX = "Seq_in_index";
    public static final String FIELD_SPECIFIC_NAME = "SPECIFIC_NAME";
    public static final String FIELD_SQL_ORIGINAL_STATEMENT = "SQL Original Statement";
    public static final String FIELD_SUB_PART = "Sub_part";
    public static final String FIELD_TABLE_COLLATION = "TABLE_COLLATION";
    public static final String FIELD_TABLE_COMMENT = "TABLE_COMMENT";
    public static final String FIELD_TABLE_NAME = "TABLE_NAME";
    public static final String FIELD_TABLE_ROWS = "TABLE_ROWS";
    public static final String FIELD_TABLESPACE_ENGINE = "ENGINE";
    public static final String FIELD_TABLESPACE_FILE_BLOCK_SIZE = "FILE_BLOCK_SIZE";
    public static final String FIELD_TABLESPACE_AUTOEXTEND_SIZE = "AUTOEXTEND_NEXT_SIZE";
    public static final String FIELD_TABLESPACE_DATA_FILE = "FILE_NAME";
    public static final String FIELD_TABLESPACE_EXTENT_SIZE = "EXTENT_SIZE";
    public static final String FIELD_TABLESPACE_INITIAL_SIZE = "INITIAL_SIZE";
    public static final String FIELD_TABLESPACE_MAX_SIZE = "MAXIMUM_SIZE";
    public static final String FIELD_TABLESPACE_NAME = "NAME";
    public static final String FIELD_TABLESPACE_NAME_REF = "TABLESPACE_NAME";
    public static final String FIELD_TABLESPACE_SPACE = "SPACE";
    public static final String FIELD_TABLESPACE_STATUS = "STATUS";
    public static final String FIELD_TABLESPACE_TABLE_SCHEMA = "TABLE_SCHEMA";
    public static final String FIELD_TRIGGER_NAME = "TRIGGER_NAME";
    public static final String FIELD_UPDATE_TIME = "UPDATE_TIME";
    public static final String FORM_FIELD_DEFINER = "definer";
    public static final String FORM_FIELD_USE_OR_REPLACE = "useOrReplace";
    public static final String FORM_FIELD_VIEW_NAME = "viewName";
    public static final String FORM_LABEL_USE_OR_REPLACE = "use or replace";
    public static final String I18N_MODIFY_VIEW_CONFIG_DEFINER = "gui.modify.view.config.definer";
    public static final String I18N_MODIFY_VIEW_CONFIG_NAME = "gui.modify.view.config.name";
    public static final String ENGINE_SUPPORT_YES = "YES";
    public static final String ENGINE_SUPPORT_DEFAULT = "DEFAULT";
    public static final String INDEX_ASC = "ASC";
    public static final String INDEX_COLLATION_ASC = "a";
    public static final String INDEX_COLLATION_DESC = "d";
    public static final String INDEX_DESC = "DESC";
    public static final String LOG_INDEX_COMMENT_FAILED = "Could not get comment for index {}";
    public static final String LOG_SYSTEM_VIEW_CREATE_TABLE_FAILED = "this is a system view, can not get 'Create Table'";
    public static final String LOG_TABLE_META_QUERY_FAILED = "query mysql {} failed, fallback to static list";
    public static final String OPTION_CHARACTER_SETS = "character sets";
    public static final String OPTION_COLLATIONS = "collations";
    public static final String OPTION_ENGINES = "engines";
    public static final String SQL_AS = " AS \n";
    public static final String SQL_AUTO_INCREMENT = "auto_increment";
    public static final String SQL_DOT = ".";
    public static final String SQL_ENUM_TYPE = "ENUM";
    public static final String SQL_FULLTEXT_INDEX_TYPE = "FULLTEXT";
    public static final String SQL_METADATA_QUOTE = "`";
    public static final String SQL_NAME_SIZE_CLOSE = ")";
    public static final String SQL_NAME_SIZE_OPEN = "(";
    public static final String SQL_ON_UPDATE_CURRENT_TIMESTAMP = "on update CURRENT_TIMESTAMP";
    public static final String SQL_PRIMARY_INDEX_NAME = "PRIMARY";
    public static final String SQL_PRIMARY_KEY_FLAG = "PRI";
    public static final String SQL_SELECT_PREVIEW_TABLE = "select * from table_name";
    public static final String SQL_SEMICOLON = ";";
    public static final String SQL_SET_TYPE = "SET";
    public static final String SQL_SINGLE_QUOTE = "'";
    public static final String SQL_SPATIAL_INDEX_TYPE = "SPATIAL";
    public static final String SQL_TABLE_NAME_EQUALS_FILTER = " AND TABLE_NAME = '";
    public static final String SQL_TYPE_SIZE_SEPARATOR = ",";
    public static final String SQL_UNDEFINED = "undefined";
    public static final String SQL_VIEW_KEYWORD = "view ";
    public static final String SQL_YES = "YES";
    public static final String SYSTEM_DATABASE_INFORMATION_SCHEMA = "information_schema";
    public static final String SYSTEM_DATABASE_MYSQL = "mysql";
    public static final String SYSTEM_DATABASE_PERFORMANCE_SCHEMA = "performance_schema";
    public static final String SYSTEM_DATABASE_SYS = "sys";
    public static final List<String> SYSTEM_DATABASES = List.of(SYSTEM_DATABASE_INFORMATION_SCHEMA,
            SYSTEM_DATABASE_PERFORMANCE_SCHEMA, SYSTEM_DATABASE_MYSQL, SYSTEM_DATABASE_SYS);
    public static final String TYPE_DATE = "DATE";
    public static final String TYPE_DATETIME = "DATETIME";
    public static final String TYPE_TIME = "TIME";
    public static final String TYPE_TIMESTAMP = "TIMESTAMP";
    public static final Map<String, ResultSetEditorTypeEnum> RESULT_SET_EDITOR_TYPE_BY_TYPE_NAME = Map.of(
            TYPE_DATE, ResultSetEditorTypeEnum.DATE,
            TYPE_TIME, ResultSetEditorTypeEnum.TIME,
            TYPE_DATETIME, ResultSetEditorTypeEnum.DATETIME,
            TYPE_TIMESTAMP, ResultSetEditorTypeEnum.TIMESTAMP
    );
    public static final Map<Integer, ResultSetEditorTypeEnum> RESULT_SET_EDITOR_TYPE_BY_JDBC_TYPE = Map.of(
            Types.DATE, ResultSetEditorTypeEnum.DATE,
            Types.TIME, ResultSetEditorTypeEnum.TIME,
            Types.TIMESTAMP, ResultSetEditorTypeEnum.TIMESTAMP
    );
    public static final String TABLE_STATUS = "select * FROM information_schema.collation_character_set_applicability limit 10000";
    public static final String TABLES_SQL = "SELECT TABLE_SCHEMA, TABLE_NAME, `ENGINE`, `VERSION`, TABLE_ROWS, DATA_LENGTH, `AUTO_INCREMENT`, CREATE_TIME, UPDATE_TIME, TABLE_COLLATION, TABLE_COMMENT, TABLESPACE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE in ('BASE TABLE','SYSTEM VIEW') AND TABLE_SCHEMA = '%s'";
    /**
     * Lists InnoDB General Tablespaces (server-scoped), excluding the InnoDB system, undo, and
     * temporary tablespaces which are not general tablespaces. LEFT JOINs INFORMATION_SCHEMA.FILES
     * for the data-file path; FILES rows require PROCESS/FILE privilege on 8.0.21+, so the path
     * may be null and must be tolerated. Works on MySQL 5.7 and 8.0.
     */
    public static final String TABLESPACES_SQL =
            "SELECT T.SPACE, T.NAME, T.ENGINE, T.FILE_BLOCK_SIZE, "
                    + "F.FILE_NAME, F.AUTOEXTEND_NEXT_SIZE, F.MAXIMUM_SIZE, "
                    + "F.TOTAL_EXTENTS, F.EXTENT_SIZE, F.INITIAL_SIZE, F.STATUS "
                    + "FROM INFORMATION_SCHEMA.TABLESPACES T "
                    + "LEFT JOIN INFORMATION_SCHEMA.FILES F ON F.TABLESPACE_NAME = T.NAME "
                    + "WHERE T.ENGINE = 'InnoDB' "
                    + "AND T.NAME NOT IN ('innodb_system','innodb_undo_001','innodb_undo_002','innodb_temporary') "
                    + "AND T.NAME NOT LIKE 'innodb_undo_%' "
                    + "AND T.NAME NOT LIKE 'innodb_temp_%' "
                    + "ORDER BY T.NAME";
    /**
     * Single-tablespace detail. The name is interpolated as an escaped string literal
     * (INFORMATION_SCHEMA does not accept a bound parameter for the identifier here).
     */
    public static final String TABLESPACE_DETAIL_SQL_TEMPLATE = TABLESPACES_SQL.replace(
            "ORDER BY T.NAME", "AND T.NAME = '%s' ORDER BY T.NAME");
    /**
     * Tables occupying a tablespace. The tablespace name is a value (not an identifier), so it is
     * bound as a prepared-statement parameter.
     */
    public static final String TABLESPACE_OCCUPYING_TABLES_SQL =
            "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLESPACE_NAME = ? AND TABLE_TYPE = 'BASE TABLE' "
                    + "ORDER BY TABLE_SCHEMA, TABLE_NAME";
    public static final String ROUTINES_SQL = "SELECT SPECIFIC_NAME, ROUTINE_COMMENT, ROUTINE_DEFINITION FROM information_schema.routines WHERE routine_type = '%s' AND ROUTINE_SCHEMA ='%s'  AND routine_name = '%s';";
    public static final String TRIGGER_SQL = "show create trigger %s.%s";
    public static final String TRIGGER_SQL_LIST = "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS where TRIGGER_SCHEMA = '%s';";
    public static final String SELECT_TABLE_COLUMNS = "SELECT * FROM information_schema.COLUMNS  WHERE TABLE_SCHEMA =  '%s'  AND TABLE_NAME =  '%s'  order by ORDINAL_POSITION";
    public static final String VIEW_DDL_SQL = "show create view %s";

    private MysqlMetaDataConstants() {
    }
}
