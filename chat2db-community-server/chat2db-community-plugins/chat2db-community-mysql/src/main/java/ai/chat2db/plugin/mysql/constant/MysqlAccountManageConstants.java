package ai.chat2db.plugin.mysql.constant;

public final class MysqlAccountManageConstants {

    public static final String ACCOUNT_DISPLAY_NAME_SEPARATOR = "@";
    public static final String ERROR_KEY_ACCOUNT_EXECUTE_FAILED = "mysql.account.executeFailed";
    public static final String ERROR_KEY_ACCOUNT_GRANTS_UNAVAILABLE = "mysql.account.grantsUnavailable";
    public static final String ERROR_KEY_ACCOUNT_LIST_UNAVAILABLE = "mysql.account.listUnavailable";
    public static final String ERROR_KEY_ACCOUNT_PREVIEW_TOKEN_MISMATCH = "mysql.account.previewTokenMismatch";
    public static final String ERROR_KEY_ACCOUNT_RENAME_TARGET_EXISTS = "mysql.account.renameTargetExists";
    public static final String FIELD_ACCOUNT_LOCKED = "account_locked";
    public static final String FIELD_HOST = "Host";
    public static final String FIELD_PLUGIN = "plugin";
    public static final String FIELD_USER = "User";
    public static final String MESSAGE_OK = "OK";
    public static final String SQL_SELECT_CURRENT_USER = "SELECT CURRENT_USER()";
    public static final String SQL_SELECT_MYSQL_USERS = "SELECT User, Host, plugin FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_MYSQL_USERS_WITH_LOCK = "SELECT User, Host, plugin, account_locked FROM mysql.user ORDER BY User, Host";
    public static final String SQL_SELECT_MYSQL_USER_BY_ACCOUNT = "SELECT 1 FROM mysql.user WHERE User = ? AND Host = ? LIMIT 1";
    public static final String VALUE_ACCOUNT_LOCKED_YES = "Y";

    private MysqlAccountManageConstants() {
    }
}
