package ai.chat2db.plugin.mysql.constant;

public final class MysqlVariableConstants {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_SESSION = "SESSION";
    public static final String SCOPE_PERSIST = "PERSIST";
    public static final String SCOPE_PERSIST_ONLY = "PERSIST_ONLY";
    public static final String KIND_VARIABLES = "VARIABLES";

    public static final String SQL_SHOW_GLOBAL_VARIABLES = "SHOW GLOBAL VARIABLES";
    public static final String SQL_SHOW_SESSION_VARIABLES = "SHOW SESSION VARIABLES";
    public static final String SQL_SHOW_GLOBAL_STATUS = "SHOW GLOBAL STATUS";
    public static final String SQL_SHOW_SESSION_STATUS = "SHOW SESSION STATUS";
    public static final String SQL_VARIABLE_INFO = """
            SELECT VARIABLE_NAME, VARIABLE_SOURCE, VARIABLE_PATH, MIN_VALUE, MAX_VALUE, SET_TIME, SET_USER, SET_HOST
            FROM performance_schema.variables_info
            """;
    public static final String SQL_SET_GLOBAL = "SET GLOBAL %s = %s";
    public static final String SQL_SET_SESSION = "SET SESSION %s = %s";
    public static final String SQL_SET_PERSIST = "SET PERSIST %s = %s";
    public static final String SQL_SET_PERSIST_ONLY = "SET PERSIST_ONLY %s = %s";

    public static final String COLUMN_VARIABLE_NAME = "VARIABLE_NAME";
    public static final String COLUMN_VARIABLE_SOURCE = "VARIABLE_SOURCE";
    public static final String COLUMN_VARIABLE_PATH = "VARIABLE_PATH";
    public static final String COLUMN_MIN_VALUE = "MIN_VALUE";
    public static final String COLUMN_MAX_VALUE = "MAX_VALUE";
    public static final String COLUMN_SET_TIME = "SET_TIME";
    public static final String COLUMN_SET_USER = "SET_USER";
    public static final String COLUMN_SET_HOST = "SET_HOST";

    public static final String ERROR_REQUIRED = "mysql.variables.required";
    public static final String ERROR_READ_ONLY = "mysql.variables.readOnly";
    public static final String ERROR_UNSUPPORTED_SCOPE = "mysql.variables.unsupportedScope";
    public static final String ERROR_INVALID_NUMBER = "mysql.variables.invalidNumber";
    public static final String ERROR_INVALID_ON_OFF = "mysql.variables.invalidOnOff";

    private MysqlVariableConstants() {
    }
}
