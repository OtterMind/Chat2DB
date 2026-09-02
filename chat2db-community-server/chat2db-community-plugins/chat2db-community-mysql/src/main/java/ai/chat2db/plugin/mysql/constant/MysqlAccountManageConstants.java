package ai.chat2db.plugin.mysql.constant;

public final class MysqlAccountManageConstants {

    public static final String ACCOUNT_DISPLAY_NAME_SEPARATOR = "@";
    public static final String ERROR_KEY_ACCOUNT_EXECUTE_FAILED = "mysql.account.executeFailed";
    public static final String ERROR_KEY_ACCOUNT_GRANTS_UNAVAILABLE = "mysql.account.grantsUnavailable";
    public static final String ERROR_KEY_ACCOUNT_LIST_UNAVAILABLE = "mysql.account.listUnavailable";
    public static final String ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH = "mysql.account.previewTokenMismatch";
    public static final String ERROR_KEY_ACCOUNT_ROLE_UNSUPPORTED = "mysql.account.roleUnsupported";
    public static final String FIELD_ACCOUNT_LOCKED = "account_locked";
    public static final String FIELD_HOST = "Host";
    public static final String FIELD_MAX_CONNECTIONS = "max_connections";
    public static final String FIELD_MAX_QUESTIONS = "max_questions";
    public static final String FIELD_MAX_UPDATES = "max_updates";
    public static final String FIELD_MAX_USER_CONNECTIONS = "max_user_connections";
    public static final String FIELD_PASSWORD_EXPIRED = "password_expired";
    public static final String FIELD_PASSWORD_LAST_CHANGED = "password_last_changed";
    public static final String FIELD_PASSWORD_LIFETIME = "password_lifetime";
    public static final String FIELD_PLUGIN = "plugin";
    public static final String FIELD_USER = "User";
    public static final String MESSAGE_OK = "OK";
    public static final String SQL_SELECT_CURRENT_USER = "SELECT CURRENT_USER()";
    public static final String SQL_SELECT_MYSQL_USERS = "SELECT User, Host, plugin FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_MYSQL_USERS_WITH_LOCK = "SELECT User, Host, plugin, account_locked FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_MYSQL_USERS_WITH_SETTINGS = "SELECT User, Host, plugin, account_locked,"
            + " password_expired, password_last_changed, password_lifetime,"
            + " max_questions, max_updates, max_connections, max_user_connections FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_PASSWORD_EXPIRATION_MYSQL_USER =
            "SELECT password_expired, password_last_changed, password_lifetime FROM mysql.user LIMIT 1";
    public static final String SQL_SELECT_RESOURCE_LIMITS_MYSQL_USER =
            "SELECT max_questions, max_updates, max_connections, max_user_connections FROM mysql.user LIMIT 1";
    public static final String VALUE_ACCOUNT_LOCKED_YES = "Y";

    private MysqlAccountManageConstants() {
    }
}
