package ai.chat2db.spi.constant;

import java.util.List;

public final class DefaultSqlBuilderConstants {

    public static final String VALUE_DOUBLE_QUOTE = SQLConstants.TAB + SQLConstants.SPACE
            + SQLConstants.DOUBLE_QUOTE;
    public static final String VALUE_DOUBLE_QUOTE_2 = SQLConstants.DOUBLE_QUOTE + SQLConstants.SPACE;
    public static final String OPERATION_TYPE_UPDATE_COPY = "UPDATE_COPY";
    public static final String VALUE_CLOSE_PAREN_VALUES_OPEN_PAREN = SQLConstants.CLOSE_PARENTHESIS
            + SQLConstants.VALUES_SQL + SQLConstants.OPEN_PARENTHESIS;
    public static final String ERROR_KEY_COPY_IN_VALUES_EMPTY_INPUT = "copyInValues.emptyInput";
    public static final String ERROR_KEY_COPY_IN_VALUES_SINGLE_COLUMN_REQUIRED =
            "copyInValues.singleColumnRequired";
    public static final String ERROR_KEY_COPY_IN_VALUES_INVALID_SELECTION = "copyInValues.invalidSelection";
    public static final String ERROR_KEY_COPY_IN_VALUES_LARGE_VALUE_REJECTED =
            "copyInValues.largeValueRejected";
    public static final String LARGE_VALUE_PREVIEW_PREFIX = "CHAT2DB_LARGE_VALUE_PREVIEW:";
    public static final String ERROR_UNSUPPORTED_DEFAULT_SQL_BUILDER_PREFIX =
            "Default SQL builder does not support ";
    public static final String ERROR_KEY_SQL_BUILDER_ORDER_BY_FAILED = "sqlBuilder.orderBy.failed";
    public static final String METHOD_BUILD_ALTER_DATABASE = "buildAlterDatabase";
    public static final String METHOD_BUILD_ALTER_SCHEMA = "buildAlterSchema";
    public static final String METHOD_BUILD_CREATE_VIEW = "buildCreateView";
    public static final String METHOD_BUILD_ALTER_VIEW = "buildAlterView";
    public static final String METHOD_BUILD_DROP_VIEW = "buildDropView";
    public static final String METHOD_BUILD_SHOW_CREATE_VIEW = "buildShowCreateView";
    public static final String METHOD_BUILD_DELETE = "buildDelete";
    public static final String METHOD_BUILD_CREATE_TABLESPACE = "buildCreateTablespace";
    public static final String METHOD_BUILD_DROP_TABLESPACE = "buildDropTablespace";
    public static final String METHOD_BUILD_RENAME_TABLESPACE = "buildRenameTablespace";
    public static final String METHOD_BUILD_ALTER_TABLESPACE_ADD_DATAFILE =
            "buildAlterTablespaceAddDatafile";

    public static final String SQL_AND = SQLConstants.SQL_AND;
    public static final String SQL_AND_2 = SQLConstants.SQL_AND_LOWER;
    public static final String SQL_COMMENT_COLUMN = SQLConstants.COMMENT_ON_COLUMN_SQL_PREFIX;
    public static final String SQL_CREATE = SQLConstants.CREATE_SQL_PREFIX;
    public static final String SQL_CREATE_TABLE = SQLConstants.CREATE_TABLE_SQL_PREFIX;
    public static final String SQL_DELETE = SQLConstants.DELETE_FROM_SQL_PREFIX;
    public static final String SQL_FROM_WHERE = SQLConstants.FROM_WHERE_SQL;
    public static final String SQL_INSERT_INTO = SQLConstants.INSERT_INTO_SQL_PREFIX;
    public static final String SQL_ON = SQLConstants.SQL_ON;
    public static final String SQL_SELECT = SQLConstants.SELECT_SQL_PREFIX;
    public static final String SQL_SELECT_COUNT_FROM = "SELECT COUNT(1) FROM ";
    public static final String SQL_SELECT_2 = SQLConstants.SELECT_ALL_FROM_SQL_PREFIX;
    public static final String SQL_SET = SQLConstants.SET_SQL_LOWER;
    public static final String SQL_SET_2 = SQLConstants.SET_SQL;
    public static final String SQL_UPDATE = SQLConstants.UPDATE_SQL_PREFIX;
    public static final String SQL_VALUES = SQLConstants.VALUES_SQL;
    public static final String SQL_WHERE = SQLConstants.WHERE_SQL_LOWER;
    public static final String SQL_WHERE_2 = SQLConstants.WHERE_SQL;
    public static final String SQL_DROP_SCHEMA_PREFIX = "DROP SCHEMA ";
    public static final List<String> COPY_IN_VALUES_BLOCKED_COLUMN_TYPES = List.of(
            "blob", "binary", "varbinary", "image", "bytea", "raw", "long raw", "bfile"
    );

    private DefaultSqlBuilderConstants() {
    }
}
