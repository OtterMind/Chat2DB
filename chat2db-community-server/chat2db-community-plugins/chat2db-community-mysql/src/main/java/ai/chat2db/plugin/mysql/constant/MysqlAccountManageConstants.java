package ai.chat2db.plugin.mysql.constant;

public final class MysqlAccountManageConstants {

    public static final String ACCOUNT_DISPLAY_NAME_SEPARATOR = "@";
    public static final String ERROR_KEY_ACCOUNT_EXECUTE_FAILED = "mysql.account.executeFailed";
    public static final String ERROR_KEY_ACCOUNT_GRANTS_UNAVAILABLE = "mysql.account.grantsUnavailable";
    public static final String ERROR_KEY_ACCOUNT_LIST_UNAVAILABLE = "mysql.account.listUnavailable";
    public static final String ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH = "mysql.account.previewTokenMismatch";
    public static final String ERROR_KEY_ACCOUNT_ROLE_UNSUPPORTED = "mysql.account.roleUnsupported";
    public static final String FIELD_ACCOUNT_LOCKED = "account_locked";
    public static final String FIELD_AUTHENTICATION_STRING = "authentication_string";
    public static final String FIELD_HOST = "Host";
    public static final String FIELD_PLUGIN = "plugin";
    public static final String FIELD_PASSWORD_EXPIRED = "password_expired";
    public static final String FIELD_USER = "User";
    public static final String FIELD_ADMIN_OPTION = "admin_option";
    public static final String FIELD_GRANTEE_HOST = "grantee_host";
    public static final String FIELD_GRANTEE_USER = "grantee_user";
    public static final String FIELD_ROLE_HOST = "role_host";
    public static final String FIELD_ROLE_USER = "role_user";
    public static final String MESSAGE_OK = "OK";
    public static final String SQL_SELECT_CURRENT_ROLE = "SELECT CURRENT_ROLE()";
    public static final String SQL_SELECT_CURRENT_USER = "SELECT CURRENT_USER()";
    public static final String SQL_SELECT_DEFAULT_ROLES = "SELECT DEFAULT_ROLE_USER, DEFAULT_ROLE_HOST FROM mysql.default_roles WHERE USER = ? AND HOST = ? ORDER BY DEFAULT_ROLE_USER, DEFAULT_ROLE_HOST";
    public static final String SQL_SELECT_MYSQL_USERS = "SELECT User, Host, plugin FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_MYSQL_USERS_WITH_LOCK = "SELECT User, Host, plugin, account_locked, "
            + "authentication_string, password_expired FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_DEFAULT_ROLE_ACCOUNTS = "SELECT DISTINCT DEFAULT_ROLE_USER AS role_user, "
            + "DEFAULT_ROLE_HOST AS role_host FROM mysql.default_roles ORDER BY DEFAULT_ROLE_USER, DEFAULT_ROLE_HOST";
    public static final String SQL_SELECT_ROLE_ACCOUNTS = "SELECT DISTINCT FROM_USER AS role_user, FROM_HOST AS role_host "
            + "FROM mysql.role_edges ORDER BY FROM_USER, FROM_HOST";
    public static final String SQL_SELECT_ROLE_EDGES = "SELECT FROM_USER AS role_user, FROM_HOST AS role_host, "
            + "TO_USER AS grantee_user, TO_HOST AS grantee_host, WITH_ADMIN_OPTION AS admin_option "
            + "FROM mysql.role_edges ORDER BY TO_USER, TO_HOST, FROM_USER, FROM_HOST";
    public static final String VALUE_ACCOUNT_LOCKED_YES = "Y";

    private MysqlAccountManageConstants() {
    }
}
